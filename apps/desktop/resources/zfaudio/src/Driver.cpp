// ZeroFrictionAudio — the macOS half of caster's virtual audio devices
// (batch A, the capture-card north star: audio as INPUT devices that never
// touch the speakers). A Core Audio HAL plugin (AudioServerPlugIn) built on
// libASPL (MIT, github.com/gavv/libASPL), installed once into
// /Library/Audio/Plug-Ins/HAL — no Apple entitlement, no kernel.
//
// Two loopback devices, one per cast track (the two-track architecture,
// ADR-003, stays visible end to end — something even a physical capture card
// cannot do, since it carries one mix):
//
//   "Zero-Friction Game Audio"  — the cast's game audio
//   "Zero-Friction Mic"         — the cast's microphone
//
// Each device exposes an OUTPUT stream (our app plays the track into it via
// AudioContext.setSinkId, exactly like the BlackHole cable before it) and an
// INPUT stream (OBS/Zoom/Discord capture from it). A ring buffer shuttles
// audio from the output side to the input side inside the plugin — no IPC
// with our app, the audio system itself is the transport.
//
// Build: see build.sh in this directory (clones a pinned libASPL, compiles a
// universal .driver bundle). Install: install.sh (admin, once) + coreaudiod
// restart.

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
// side (apps, i.e. our mixer, rendering into it). Consumer: the input side
// (apps capturing). Underflow reads as silence — a device with no producer
// is silent, exactly like an unplugged capture card. Overflow drops oldest.
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
        const UInt32 readIndex = readIndex_.load(std::memory_order_relaxed);
        UInt32 available = 0;
        const UInt32 writeIndex = writeIndex_.load(std::memory_order_acquire);
        if (writeIndex > readIndex) {
            available = writeIndex - readIndex;
        }
        if (available < frameCount) {
            // Underflow: silence the tail (the consumer is simply ahead).
            std::memset(samples, 0, frameCount * ChannelCount * sizeof(Float32));
            if (available == 0) {
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
                                                 const char* name)
{
    aspl::DeviceParameters params;
    params.Name = name;
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

std::shared_ptr<aspl::Driver> CreateZeroFrictionAudioDriver()
{
    auto context = std::make_shared<aspl::Context>();
    auto plugin = std::make_shared<aspl::Plugin>(context);

    plugin->AddDevice(MakeLoopbackDevice(context, "Zero-Friction Game Audio"));
    plugin->AddDevice(MakeLoopbackDevice(context, "Zero-Friction Mic"));

    return std::make_shared<aspl::Driver>(context, plugin);
}

// Keep the driver alive for the lifetime of coreaudiod.
std::shared_ptr<aspl::Driver> gDriver;

} // namespace

extern "C" void* ZeroFrictionAudioEntryPoint(CFAllocatorRef allocator, CFUUIDRef typeUUID)
{
    (void)allocator;

    if (!CFEqual(typeUUID, kAudioServerPlugInTypeUUID)) {
        return nullptr;
    }

    gDriver = CreateZeroFrictionAudioDriver();

    return gDriver->GetReference();
}
