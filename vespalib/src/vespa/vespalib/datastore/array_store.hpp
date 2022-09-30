// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "array_store.h"
#include "compaction_spec.h"
#include "entry_ref_filter.h"
#include "datastore.hpp"
#include "large_array_buffer_type.hpp"
#include "small_array_buffer_type.hpp"
#include <atomic>
#include <algorithm>

namespace vespalib::datastore {

template <typename EntryT, typename RefT, typename TypeMapperT>
void
ArrayStore<EntryT, RefT, TypeMapperT>::initArrayTypes(const ArrayStoreConfig &cfg, std::shared_ptr<alloc::MemoryAllocator> memory_allocator)
{
    _largeArrayTypeId = _store.addType(&_largeArrayType);
    assert(_largeArrayTypeId == 0);
    _smallArrayTypes.reserve(_maxSmallArrayTypeId);
    for (uint32_t type_id = 1; type_id <= _maxSmallArrayTypeId; ++type_id) {
        const AllocSpec &spec = cfg.spec_for_type_id(type_id);
        size_t arraySize = _mapper.get_array_size(type_id);
        _smallArrayTypes.emplace_back(arraySize, spec, memory_allocator, _mapper);
        uint32_t act_type_id = _store.addType(&_smallArrayTypes.back());
        assert(type_id == act_type_id);
    }
}

template <typename EntryT, typename RefT, typename TypeMapperT>
ArrayStore<EntryT, RefT, TypeMapperT>::ArrayStore(const ArrayStoreConfig &cfg, std::shared_ptr<alloc::MemoryAllocator> memory_allocator)
    : ArrayStore(cfg, memory_allocator, TypeMapper())
{
}

template <typename EntryT, typename RefT, typename TypeMapperT>
ArrayStore<EntryT, RefT, TypeMapperT>::ArrayStore(const ArrayStoreConfig &cfg, std::shared_ptr<alloc::MemoryAllocator> memory_allocator,
                                                  TypeMapper&& mapper)
    : _largeArrayTypeId(0),
      _maxSmallArrayTypeId(cfg.maxSmallArrayTypeId()),
      _maxSmallArraySize(mapper.get_array_size(_maxSmallArrayTypeId)),
      _store(),
      _mapper(std::move(mapper)),
      _smallArrayTypes(),
      _largeArrayType(cfg.spec_for_type_id(0), memory_allocator, _mapper)
{
    initArrayTypes(cfg, std::move(memory_allocator));
    _store.init_primary_buffers();
    if (cfg.enable_free_lists()) {
        _store.enableFreeLists();
    }
}

template <typename EntryT, typename RefT, typename TypeMapperT>
ArrayStore<EntryT, RefT, TypeMapperT>::~ArrayStore()
{
    _store.clearHoldLists();
    _store.dropBuffers();
}

template <typename EntryT, typename RefT, typename TypeMapperT>
EntryRef
ArrayStore<EntryT, RefT, TypeMapperT>::add(const ConstArrayRef &array)
{
    if (array.size() == 0) {
        return EntryRef();
    }
    if (array.size() <= _maxSmallArraySize) {
        return addSmallArray(array);
    } else {
        return addLargeArray(array);
    }
}

template <typename EntryT, typename RefT, typename TypeMapperT>
EntryRef
ArrayStore<EntryT, RefT, TypeMapperT>::addSmallArray(const ConstArrayRef &array)
{
    uint32_t typeId = _mapper.get_type_id(array.size());
    using NoOpReclaimer = DefaultReclaimer<EntryT>;
    return _store.template freeListAllocator<EntryT, NoOpReclaimer>(typeId).allocArray(array).ref;
}

template <typename EntryT, typename RefT, typename TypeMapperT>
EntryRef
ArrayStore<EntryT, RefT, TypeMapperT>::addLargeArray(const ConstArrayRef &array)
{
    using NoOpReclaimer = DefaultReclaimer<LargeArray>;
    auto handle = _store.template freeListAllocator<LargeArray, NoOpReclaimer>(_largeArrayTypeId)
            .alloc(array.cbegin(), array.cend());
    auto& state = _store.getBufferState(RefT(handle.ref).bufferId());
    state.incExtraUsedBytes(sizeof(EntryT) * array.size());
    return handle.ref;
}

template <typename EntryT, typename RefT, typename TypeMapperT>
void
ArrayStore<EntryT, RefT, TypeMapperT>::remove(EntryRef ref)
{
    if (ref.valid()) {
        RefT internalRef(ref);
        uint32_t typeId = _store.getTypeId(internalRef.bufferId());
        if (typeId != _largeArrayTypeId) {
            size_t arraySize = _mapper.get_array_size(typeId);
            _store.holdElem(ref, arraySize);
        } else {
            _store.holdElem(ref, 1, sizeof(EntryT) * get(ref).size());
        }
    }
}

namespace arraystore {

template <typename EntryT, typename RefT, typename TypeMapperT>
class CompactionContext : public ICompactionContext {
private:
    using ArrayStoreType = ArrayStore<EntryT, RefT, TypeMapperT>;
    DataStoreBase &_dataStore;
    ArrayStoreType &_store;
    std::vector<uint32_t> _bufferIdsToCompact;
    EntryRefFilter _filter;

public:
    CompactionContext(DataStoreBase &dataStore,
                      ArrayStoreType &store,
                      std::vector<uint32_t> bufferIdsToCompact)
        : _dataStore(dataStore),
          _store(store),
          _bufferIdsToCompact(std::move(bufferIdsToCompact)),
          _filter(RefT::numBuffers(), RefT::offset_bits)
    {
        _filter.add_buffers(_bufferIdsToCompact);
    }
    ~CompactionContext() override {
        _dataStore.finishCompact(_bufferIdsToCompact);
    }
    void compact(vespalib::ArrayRef<AtomicEntryRef> refs) override {
        for (auto &atomic_entry_ref : refs) {
            auto ref = atomic_entry_ref.load_relaxed();
            if (ref.valid() && _filter.has(ref)) {
                EntryRef newRef = _store.add(_store.get(ref));
                atomic_entry_ref.store_release(newRef);
            }
        }
    }
};

}

template <typename EntryT, typename RefT, typename TypeMapperT>
ICompactionContext::UP
ArrayStore<EntryT, RefT, TypeMapperT>::compactWorst(CompactionSpec compaction_spec, const CompactionStrategy &compaction_strategy)
{
    std::vector<uint32_t> bufferIdsToCompact = _store.startCompactWorstBuffers(compaction_spec, compaction_strategy);
    return std::make_unique<arraystore::CompactionContext<EntryT, RefT, TypeMapperT>>
        (_store, *this, std::move(bufferIdsToCompact));
}

template <typename EntryT, typename RefT, typename TypeMapperT>
vespalib::AddressSpace
ArrayStore<EntryT, RefT, TypeMapperT>::addressSpaceUsage() const
{
    return _store.getAddressSpaceUsage();
}

template <typename EntryT, typename RefT, typename TypeMapperT>
const BufferState &
ArrayStore<EntryT, RefT, TypeMapperT>::bufferState(EntryRef ref) const
{
    RefT internalRef(ref);
    return _store.getBufferState(internalRef.bufferId());
}

template <typename EntryT, typename RefT, typename TypeMapperT>
ArrayStoreConfig
ArrayStore<EntryT, RefT, TypeMapperT>::optimizedConfigForHugePage(uint32_t maxSmallArrayTypeId,
                                                                  size_t hugePageSize,
                                                                  size_t smallPageSize,
                                                                  size_t minNumArraysForNewBuffer,
                                                                  float allocGrowFactor)
{
    TypeMapper mapper;
    return optimizedConfigForHugePage(maxSmallArrayTypeId,
                                      mapper,
                                      hugePageSize,
                                      smallPageSize,
                                      minNumArraysForNewBuffer,
                                      allocGrowFactor);
}

template <typename EntryT, typename RefT, typename TypeMapperT>
ArrayStoreConfig
ArrayStore<EntryT, RefT, TypeMapperT>::optimizedConfigForHugePage(uint32_t maxSmallArrayTypeId,
                                                                  const TypeMapper& mapper,
                                                                  size_t hugePageSize,
                                                                  size_t smallPageSize,
                                                                  size_t minNumArraysForNewBuffer,
                                                                  float allocGrowFactor)
{
    return ArrayStoreConfig::optimizeForHugePage(maxSmallArrayTypeId,
                                                 [&](uint32_t type_id) noexcept { return mapper.get_array_size(type_id); },
                                                 hugePageSize,
                                                 smallPageSize,
                                                 sizeof(EntryT),
                                                 RefT::offsetSize(),
                                                 minNumArraysForNewBuffer,
                                                 allocGrowFactor);
}

}
