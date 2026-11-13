//! Installs Caster's virtual audio devices, "Caster Audio" and "Caster Mic"
//! (ADR-030): the `CasterAudio.driver` HAL plug-in the app bundle carries,
//! copied into `/Library/Audio/Plug-Ins/HAL` with macOS's own administrator
//! password dialog, then `coreaudiod` restarted so it loads the plug-in.
//!
//! The same step removes `ZeroFrictionAudio.driver`, the Electron receiver's
//! plug-in (ADR-008) these devices replace. The receiver never sees the
//! password: `osascript` asks for it and runs the commands as root.

use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

/// The plug-in's bundle name, in the app's resources and once installed.
pub const DRIVER_BUNDLE: &str = "CasterAudio.driver";
/// Where Core Audio loads HAL plug-ins from.
const HAL_PLUGINS_DIR: &str = "/Library/Audio/Plug-Ins/HAL";
/// The ADR-008 plug-in Caster's replaces; removed by the same install.
const REPLACED_DRIVER_BUNDLE: &str = "ZeroFrictionAudio.driver";
/// The devices the plug-in creates. Both must appear for an install to count.
pub const DEVICE_NAMES: [&str; 2] = [crate::audio::CASTER_AUDIO_DEVICE, crate::audio::CASTER_MIC_DEVICE];
/// `coreaudiod` comes back in about a second; give the devices this long to
/// appear before calling the install failed.
const DEVICES_APPEAR_WITHIN: Duration = Duration::from_secs(10);
const DEVICES_POLL_EVERY: Duration = Duration::from_millis(250);
/// AppleScript's "User canceled." error number, which osascript prints when
/// the password dialog is cancelled.
const USER_CANCELED: &str = "(-128)";

/// How an install ended. Declined is the user's choice, not an error.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Installed,
    Declined,
    Failed,
}

impl Outcome {
    pub fn as_str(self) -> &'static str {
        match self {
            Outcome::Installed => "installed",
            Outcome::Declined => "declined",
            Outcome::Failed => "failed",
        }
    }
}

/// One install at a time: a second click while the dialog is up is ignored.
static INSTALLING: AtomicBool = AtomicBool::new(false);

/// The plug-in inside the app bundle, if this build carries it.
pub fn bundled_driver(resource_dir: &Path) -> Option<PathBuf> {
    let path = resource_dir.join(DRIVER_BUNDLE);
    path.join("Contents/Info.plist").is_file().then_some(path)
}

/// Installs the plug-in from `source`, then waits until `devices_present`
/// reports both devices. `None` while another install is running.
pub fn install(source: &Path, devices_present: impl Fn() -> bool) -> Option<Outcome> {
    if INSTALLING.swap(true, Ordering::AcqRel) {
        log::info!(target: "virtual-audio", "install already running");
        return None;
    }
    let outcome = install_once(source, devices_present);
    INSTALLING.store(false, Ordering::Release);
    Some(outcome)
}

fn install_once(source: &Path, devices_present: impl Fn() -> bool) -> Outcome {
    // The root shell cannot read an app kept in a privacy-protected folder
    // (Documents, Downloads, Desktop): `cp` fails "Operation not permitted",
    // found live 2026-11-14. Copy the plug-in out as the user first.
    let stage = std::env::temp_dir().join(format!("caster-audio-install-{}", std::process::id()));
    let staged = match stage_driver(source, &stage) {
        Ok(staged) => staged,
        Err(e) => {
            log::error!(target: "virtual-audio", "could not stage {} from {}: {e}", DRIVER_BUNDLE, source.display());
            return Outcome::Failed;
        }
    };
    let outcome = install_staged(&staged, devices_present);
    if let Err(e) = std::fs::remove_dir_all(&stage) {
        log::warn!(target: "virtual-audio", "staging folder left behind: {e}");
    }
    outcome
}

/// Copies the bundle into an empty `stage` folder with `cp -R` (keeps the
/// signature's files exactly as they are).
fn stage_driver(source: &Path, stage: &Path) -> std::io::Result<PathBuf> {
    let _ = std::fs::remove_dir_all(stage);
    std::fs::create_dir_all(stage)?;
    let staged = stage.join(DRIVER_BUNDLE);
    let status = Command::new("/bin/cp").arg("-R").arg(source).arg(&staged).status()?;
    if !status.success() {
        return Err(std::io::Error::other(format!("cp exited with {status}")));
    }
    Ok(staged)
}

fn install_staged(source: &Path, devices_present: impl Fn() -> bool) -> Outcome {
    let hal = Path::new(HAL_PLUGINS_DIR);
    let script = applescript(&shell_command(source, hal));
    log::info!(target: "virtual-audio", "installing {} (macOS asks for the password)", DRIVER_BUNDLE);
    let output = match Command::new("/usr/bin/osascript").arg("-e").arg(&script).output() {
        Ok(output) => output,
        Err(e) => {
            log::error!(target: "virtual-audio", "osascript did not start: {e}");
            return Outcome::Failed;
        }
    };
    let stderr = String::from_utf8_lossy(&output.stderr);
    let on_disk = hal.join(DRIVER_BUNDLE).join("Contents/Info.plist").is_file();
    let outcome = classify(output.status.success(), &stderr, on_disk);
    match outcome {
        Outcome::Declined => {
            log::info!(target: "virtual-audio", "password dialog cancelled — not installed");
            return outcome;
        }
        Outcome::Failed => {
            log::error!(
                target: "virtual-audio",
                "install failed: status {:?}, on disk {on_disk}, osascript: {}",
                output.status.code(),
                stderr.trim()
            );
            return outcome;
        }
        Outcome::Installed => {}
    }
    let started = Instant::now();
    while started.elapsed() < DEVICES_APPEAR_WITHIN {
        if devices_present() {
            log::info!(target: "virtual-audio", "installed — {} and {} appeared after {} ms", DEVICE_NAMES[0], DEVICE_NAMES[1], started.elapsed().as_millis());
            return Outcome::Installed;
        }
        std::thread::sleep(DEVICES_POLL_EVERY);
    }
    log::error!(target: "virtual-audio", "installed on disk, but the devices did not appear within {} s", DEVICES_APPEAR_WITHIN.as_secs());
    Outcome::Failed
}

/// Reads osascript's result. A cancelled password dialog exits non-zero with
/// AppleScript error −128 on stderr; anything else non-zero, or a plug-in
/// that is not on disk afterwards, is a failure.
fn classify(succeeded: bool, stderr: &str, on_disk: bool) -> Outcome {
    if !succeeded {
        return if stderr.contains(USER_CANCELED) { Outcome::Declined } else { Outcome::Failed };
    }
    if on_disk { Outcome::Installed } else { Outcome::Failed }
}

/// The root shell command: copy the plug-in in beside the installed one,
/// and only once that worked remove the old ones and move it into place (a
/// failed copy must not leave the Mac without a plug-in — found live
/// 2026-11-14 with the removal first). Then restart `coreaudiod` (launchd
/// brings it back). `killall` exits non-zero when coreaudiod is not
/// running, which must not fail the install.
fn shell_command(source: &Path, hal: &Path) -> String {
    let dest = hal.join(DRIVER_BUNDLE);
    let incoming = hal.join(format!("{DRIVER_BUNDLE}.new"));
    let replaced = hal.join(REPLACED_DRIVER_BUNDLE);
    format!(
        "/bin/mkdir -p {hal} && /bin/rm -rf {incoming} && /bin/cp -R {source} {incoming} && \
         /bin/rm -rf {dest} {replaced} && /bin/mv {incoming} {dest} && (/usr/bin/killall coreaudiod || true)",
        hal = shell_quote(&hal.to_string_lossy()),
        incoming = shell_quote(&incoming.to_string_lossy()),
        dest = shell_quote(&dest.to_string_lossy()),
        replaced = shell_quote(&replaced.to_string_lossy()),
        source = shell_quote(&source.to_string_lossy()),
    )
}

/// `do shell script` with macOS's administrator password dialog.
fn applescript(shell: &str) -> String {
    format!("do shell script {} with administrator privileges", applescript_string(shell))
}

/// One shell word: single quotes, with each `'` closed, escaped and reopened.
fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

/// An AppleScript string literal.
fn applescript_string(s: &str) -> String {
    format!("\"{}\"", s.replace('\\', r"\\").replace('"', "\\\""))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_cancelled_password_dialog_is_declined_not_failed() {
        let cancelled = "0:93: execution error: User canceled. (-128)\n";
        assert_eq!(classify(false, cancelled, false), Outcome::Declined);
        assert_eq!(classify(false, "0:93: execution error: cp: …: Operation not permitted (1)\n", false), Outcome::Failed);
        assert_eq!(classify(true, "", true), Outcome::Installed);
        // The script said yes, but the plug-in is not there.
        assert_eq!(classify(true, "", false), Outcome::Failed);
    }

    #[test]
    fn the_install_copies_first_then_replaces_both_plugins_and_restarts_coreaudiod() {
        let cmd = shell_command(Path::new("/tmp/stage/CasterAudio.driver"), Path::new(HAL_PLUGINS_DIR));
        assert_eq!(
            cmd,
            "/bin/mkdir -p '/Library/Audio/Plug-Ins/HAL' && \
             /bin/rm -rf '/Library/Audio/Plug-Ins/HAL/CasterAudio.driver.new' && \
             /bin/cp -R '/tmp/stage/CasterAudio.driver' '/Library/Audio/Plug-Ins/HAL/CasterAudio.driver.new' && \
             /bin/rm -rf '/Library/Audio/Plug-Ins/HAL/CasterAudio.driver' '/Library/Audio/Plug-Ins/HAL/ZeroFrictionAudio.driver' && \
             /bin/mv '/Library/Audio/Plug-Ins/HAL/CasterAudio.driver.new' '/Library/Audio/Plug-Ins/HAL/CasterAudio.driver' && \
             (/usr/bin/killall coreaudiod || true)"
        );
    }

    #[test]
    fn staging_copies_the_whole_bundle_out() {
        let root = std::env::temp_dir().join(format!("caster-va-stage-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let source = root.join("app").join(DRIVER_BUNDLE);
        std::fs::create_dir_all(source.join("Contents/MacOS")).unwrap();
        std::fs::write(source.join("Contents/Info.plist"), "plist").unwrap();
        std::fs::write(source.join("Contents/MacOS/CasterAudio"), "bin").unwrap();
        let staged = stage_driver(&source, &root.join("stage")).unwrap();
        assert_eq!(std::fs::read_to_string(staged.join("Contents/Info.plist")).unwrap(), "plist");
        assert_eq!(std::fs::read_to_string(staged.join("Contents/MacOS/CasterAudio")).unwrap(), "bin");
        std::fs::remove_dir_all(&root).unwrap();
    }

    /// The app may sit in a folder whose name has spaces, quotes or
    /// backslashes; the path must reach `cp` as one word, unchanged.
    #[test]
    fn an_odd_app_path_reaches_the_shell_as_one_word() {
        let odd = "/Users/a/My \"Apps\"/it's\\here/Caster.app/Contents/Resources/CasterAudio.driver";
        let script = applescript(&shell_command(Path::new(odd), Path::new(HAL_PLUGINS_DIR)));
        // Undo the AppleScript literal, then split the shell command the way
        // sh would: the source must come back as exactly the original path.
        let literal = script.strip_prefix("do shell script \"").unwrap().strip_suffix("\" with administrator privileges").unwrap();
        let shell = unescape_applescript(literal);
        let words = split_shell_words(&shell);
        let cp = words.iter().position(|w| w == "/bin/cp").unwrap();
        assert_eq!(words[cp + 2], odd);
    }

    fn unescape_applescript(s: &str) -> String {
        let mut out = String::new();
        let mut chars = s.chars();
        while let Some(c) = chars.next() {
            out.push(if c == '\\' { chars.next().unwrap() } else { c });
        }
        out
    }

    /// Enough of sh's word splitting for this command: single quotes,
    /// backslash escapes outside them, whitespace.
    fn split_shell_words(s: &str) -> Vec<String> {
        let (mut words, mut word, mut in_word) = (Vec::new(), String::new(), false);
        let mut chars = s.chars();
        while let Some(c) = chars.next() {
            match c {
                '\'' => {
                    in_word = true;
                    for q in chars.by_ref() {
                        if q == '\'' {
                            break;
                        }
                        word.push(q);
                    }
                }
                '\\' => {
                    in_word = true;
                    word.push(chars.next().unwrap());
                }
                c if c.is_whitespace() => {
                    if in_word {
                        words.push(std::mem::take(&mut word));
                        in_word = false;
                    }
                }
                c => {
                    in_word = true;
                    word.push(c);
                }
            }
        }
        if in_word {
            words.push(word);
        }
        words
    }

    #[test]
    fn only_a_bundle_with_an_info_plist_counts_as_bundled() {
        let dir = std::env::temp_dir().join(format!("caster-va-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join(DRIVER_BUNDLE)).unwrap();
        assert_eq!(bundled_driver(&dir), None);
        std::fs::create_dir_all(dir.join(DRIVER_BUNDLE).join("Contents")).unwrap();
        std::fs::write(dir.join(DRIVER_BUNDLE).join("Contents/Info.plist"), "").unwrap();
        assert_eq!(bundled_driver(&dir), Some(dir.join(DRIVER_BUNDLE)));
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
