// CasterAudio: Caster's virtual audio devices for OBS on macOS (ADR-030,
// which renames and ports ADR-008's ZeroFrictionAudio to the native
// receiver). A Core Audio HAL plugin (AudioServerPlugIn) built on libASPL
// (MIT, github.com/gavv/libASPL), installed once into
// /Library/Audio/Plug-Ins/HAL. No Apple entitlement, no kernel extension.
//
// Two loopback devices, one per cast track (ADR-003: the tracks never mix):
//
//   "Caster Audio"  the cast's game audio
//   "Caster Mic"    the phone's microphone
//
// Each device has an OUTPUT stream (the receiver plays the track into it)
// and an INPUT stream (OBS captures from it). A ring buffer inside the plugin
// carries audio from the output side to the input side, so the receiver and
// the plugin need no IPC: the audio system itself is the transport.
//
// Build: build.sh in this directory (clones a pinned libASPL, compiles a
// universal .driver bundle). The receiver installs it from its bundle.

#include <aspl/Driver.hpp>

#include <CoreAudio/AudioServerPlugIn.h>

#include <atomic>
#include <cstring>
#include <memory>
#include <vector>

namespace {

constexpr UInt32 SampleRate = 48000;
constexpr UInt32 ChannelCount = 2;

// Lock-free single-producer/single-consumer ring of interleaved Float32
// frames (the plugin's native stream format). Producer: the device's output
// side (apps, i.e. the receiver, rendering into it). Consumer: the input side
// (apps capturing). Underflow reads as silence — a device with no producer
// is silent, exactly like an unplugged capture card.
//
// The consumer never trails the producer by more than MaxLagFrames: when it
// does (a capture starts while the receiver has been playing for minutes, or
// starts again after a pause), it jumps to the newest audio. Without that
// jump (ADR-008's version, found live 2026-11-14) a new capture first played
// up to ~1 s of old audio left in the ring, then stayed a random distance
// (up to the ring's length) behind the producer.
class RingBuffer
{
public:
    explicit RingBuffer(UInt32 capacityFrames)
        : buffer_(RoundUpPow2(capacityFrames) * ChannelCount, 0.0f)
        , mask_(RoundUpPow2(capacityFrames) - 1)
    {
    }

    void Write(const Float32* samples, UInt32 frameCount)
    {
        UInt32 writeIndex = writeIndex_.load(std::memory_order_relaxed);
        for (UInt32 i = 0; i < frameCount; i++) {
            const UInt32 slot = (writeIndex + i) & mask_;
            for (UInt32 c = 0; c < ChannelCount; c++) {
                buffer_[slot * ChannelCount + c] = samples[i * ChannelCount + c];
            }
        }
        writeIndex_.store(writeIndex + frameCount, std::memory_order_release);
    }

    void Read(Float32* samples, UInt32 frameCount)
    {
        UInt32 readIndex = readIndex_.load(std::memory_order_relaxed);
        const UInt32 writeIndex = writeIndex_.load(std::memory_order_acquire);
        // Unsigned difference: correct across the 32-bit wrap (~25 h).
        UInt32 available = writeIndex - readIndex;
        if (available > (1u << 31)) {
            available = 0;  // the reader is ahead (cannot happen; be safe)
            readIndex = writeIndex;
        }
        if (available > MaxLagFrames) {
            // Too far behind: what lies there is old or already overwritten.
            // Keep none of it (keeping two IO buffers still let 8 ms of old
            // audio through, found live): this read is silent, the next one
            // reads what the producer writes from now on.
            readIndex = writeIndex;
            available = 0;
        }
        if (available < frameCount) {
            // Underflow: silence the tail (the consumer is simply ahead).
            std::memset(samples, 0, frameCount * ChannelCount * sizeof(Float32));
            if (available == 0) {
                readIndex_.store(readIndex, std::memory_order_release);
                return;
            }
            frameCount = available;
        }
        for (UInt32 i = 0; i < frameCount; i++) {
            const UInt32 slot = (readIndex + i) & mask_;
            for (UInt32 c = 0; c < ChannelCount; c++) {
                samples[i * ChannelCount + c] = buffer_[slot * ChannelCount + c];
            }
        }
        readIndex_.store(readIndex + frameCount, std::memory_order_release);
    }

private:
    // 170 ms at 48 kHz: well above one IO buffer, well below the ring.
    static constexpr UInt32 MaxLagFrames = 8192;

    static UInt32 RoundUpPow2(UInt32 value)
    {
        UInt32 result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    std::vector<Float32> buffer_;
    const UInt32 mask_;
    std::atomic<UInt32> readIndex_{0};
    std::atomic<UInt32> writeIndex_{0};
};

// One loopback device: output stream in, input stream out, ring between.
class LoopbackHandler : public aspl::IORequestHandler
{
public:
    // ~0.7 s at 48 kHz — absorbs scheduling jitter without unbounded latency.
    explicit LoopbackHandler(std::shared_ptr<RingBuffer> ring)
        : ring_(std::move(ring))
    {
    }

    // Output stream: what apps rendered INTO the device (mixed by libASPL).
    void OnWriteMixedOutput(const std::shared_ptr<aspl::Stream>& stream,
        Float64 /*zeroTimestamp*/,
        Float64 /*timestamp*/,
        const void* bytes,
        UInt32 bytesCount) override
    {
        const auto* samples = static_cast<const Float32*>(bytes);
        ring_->Write(samples, bytesCount / (ChannelCount * sizeof(Float32)));
    }

    // Output stream without mixing (fallback path if mixing is disabled).
    void OnWriteClientOutput(const std::shared_ptr<aspl::Client>& /*client*/,
        const std::shared_ptr<aspl::Stream>& /*stream*/,
        Float64 /*zeroTimestamp*/,
        Float64 /*timestamp*/,
        const Float32* frames,
        UInt32 frameCount,
        UInt32 /*channelCount*/) override
    {
        ring_->Write(frames, frameCount);
    }

    // Input stream: what capturing apps read FROM the device.
    void OnReadClientInput(const std::shared_ptr<aspl::Client>& /*client*/,
        const std::shared_ptr<aspl::Stream>& /*stream*/,
        Float64 /*zeroTimestamp*/,
        Float64 /*timestamp*/,
        void* bytes,
        UInt32 bytesCount) override
    {
        auto* samples = static_cast<Float32*>(bytes);
        ring_->Read(samples, bytesCount / (ChannelCount * sizeof(Float32)));
    }

private:
    const std::shared_ptr<RingBuffer> ring_;
};

std::shared_ptr<aspl::Device> MakeLoopbackDevice(const std::shared_ptr<aspl::Context>& context,
                                                 const char* name,
                                                 const char* uid)
{
    aspl::DeviceParameters params;
    params.Name = name;
    params.Manufacturer = "Caster";
    // A fixed UID: apps such as OBS remember a device by it.
    params.DeviceUID = uid;
    params.ModelUID = "app.caster.audio.loopback";
    params.SampleRate = SampleRate;
    params.ChannelCount = ChannelCount;
    params.EnableMixing = true;

    auto device = std::make_shared<aspl::Device>(context, params);
    // The output side is what our app plays into; the input side is what
    // other apps capture from — same physical device, looped by the ring.
    device->AddStreamWithControlsAsync(aspl::Direction::Output);
    device->AddStreamWithControlsAsync(aspl::Direction::Input);

    device->SetIOHandler(std::make_shared<LoopbackHandler>(
        std::make_shared<RingBuffer>(SampleRate * 2 / 3)));

    return device;
}

std::shared_ptr<aspl::Driver> CreateCasterAudioDriver()
{
    auto context = std::make_shared<aspl::Context>();
    aspl::PluginParameters pluginParams;
    pluginParams.Manufacturer = "Caster";
    auto plugin = std::make_shared<aspl::Plugin>(context, pluginParams);

    plugin->AddDevice(MakeLoopbackDevice(context, "Caster Audio", "app.caster.audio.CasterAudio"));
    plugin->AddDevice(MakeLoopbackDevice(context, "Caster Mic", "app.caster.audio.CasterMic"));

    return std::make_shared<aspl::Driver>(context, plugin);
}

// Keep the driver alive for the lifetime of coreaudiod.
std::shared_ptr<aspl::Driver> gDriver;

} // namespace

extern "C" void* CasterAudioEntryPoint(CFAllocatorRef allocator, CFUUIDRef typeUUID)
{
    (void)allocator;

    if (!CFEqual(typeUUID, kAudioServerPlugInTypeUUID)) {
        return nullptr;
    }

    gDriver = CreateCasterAudioDriver();

    return gDriver->GetReference();
}
