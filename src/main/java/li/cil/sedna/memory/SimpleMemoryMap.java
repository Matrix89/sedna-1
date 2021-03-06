package li.cil.sedna.memory;

import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.api.memory.MemoryRange;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public final class SimpleMemoryMap implements MemoryMap {
    private final Map<MemoryMappedDevice, MemoryRange> devices = new HashMap<>();

    // For device IO we often get sequential access to the same range/device, so we remember the last one as a cache.
    private MemoryRange cache;

    @Override
    public OptionalLong findFreeRange(long start, final long end, final int size) {
        if (size == 0) {
            return OptionalLong.empty();
        }

        if (Long.compareUnsigned(end, start) < 0) {
            return OptionalLong.empty();
        }

        if (Long.compareUnsigned(end - start, size - 1) < 0) {
            return OptionalLong.empty();
        }

        if (Long.compareUnsigned(start, -1L - size) > 0) {
            return OptionalLong.empty();
        }

        // Always align to 64 bit. Otherwise device I/O may require load/stores of
        // individual byte values, which most device implementations do not support.
        final int alignment = (int) (start % Sizes.SIZE_64_BYTES);
        if (alignment != 0) {
            start = start + (Sizes.SIZE_64_BYTES - alignment);
        }

        final MemoryRange candidateRange = new MemoryRange(null, start, start + size);
        for (final MemoryRange existingRange : devices.values()) {
            if (existingRange.intersects(candidateRange)) {
                if (existingRange.end != -1L) { // Avoid overflow.
                    return findFreeRange(existingRange.end + 1, end, size);
                } else {
                    return OptionalLong.empty();
                }
            }
        }

        return OptionalLong.of(start);
    }

    @Override
    public boolean addDevice(final long address, final MemoryMappedDevice device) {
        if (devices.containsKey(device)) {
            return false;
        }

        final MemoryRange deviceRange = new MemoryRange(device, address);
        if (devices.values().stream().anyMatch(range -> range.intersects(deviceRange))) {
            return false;
        }

        devices.put(device, deviceRange);
        return true;
    }

    @Override
    public void removeDevice(final MemoryMappedDevice device) {
        devices.remove(device);
        if (cache != null && cache.device == device) {
            cache = null;
        }
    }

    @Override
    public Optional<MemoryRange> getMemoryRange(final MemoryMappedDevice device) {
        return Optional.ofNullable(devices.get(device));
    }

    @Nullable
    @Override
    public MemoryRange getMemoryRange(final long address) {
        final MemoryRange cachedValue = cache; // Copy to local to avoid threading issues.
        if (cachedValue != null && cachedValue.contains(address)) {
            return cachedValue;
        }

        for (final MemoryRange range : devices.values()) {
            if (range.contains(address)) {
                cache = range;
                return range;
            }
        }

        return null;
    }

    @Override
    public void setDirty(final MemoryRange range, final int offset) {
        // todo implement tracking dirty bits; really need this if we want to add a frame buffer.
    }

    @Override
    public long load(final long address, final int sizeLog2) throws MemoryAccessException {
        final MemoryRange range = getMemoryRange(address);
        if (range != null && (range.device.getSupportedSizes() & (1 << sizeLog2)) != 0) {
            return range.device.load((int) (address - range.start), sizeLog2);
        }
        return 0;
    }

    @Override
    public void store(final long address, final long value, final int sizeLog2) throws MemoryAccessException {
        final MemoryRange range = getMemoryRange(address);
        if (range != null && (range.device.getSupportedSizes() & (1 << sizeLog2)) != 0) {
            range.device.store((int) (address - range.start), value, sizeLog2);
        }
    }
}
