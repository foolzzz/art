/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "compact_dex_debug_info.h"

#include "compact_dex_utils.h"
#include "leb128.h"

namespace art {

constexpr size_t CompactDexDebugInfoOffsetTable::kElementsPerIndex;

CompactDexDebugInfoOffsetTable::Accessor::Accessor(const uint8_t* data_begin,
                                                   uint32_t debug_info_base,
                                                   uint32_t debug_info_table_offset)
    : table_(reinterpret_cast<const uint32_t*>(data_begin + debug_info_table_offset)),
      debug_info_base_(debug_info_base),
      data_begin_(data_begin) {}

uint32_t CompactDexDebugInfoOffsetTable::Accessor::GetDebugInfoOffset(uint32_t method_idx) const {
  const uint32_t offset = table_[method_idx / kElementsPerIndex];
  const size_t bit_index = method_idx % kElementsPerIndex;

  const uint8_t* block = data_begin_ + offset;
  uint16_t bit_mask = *block;
  ++block;
  bit_mask = (bit_mask << kBitsPerByte) | *block;
  ++block;
  if ((bit_mask & (1 << bit_index)) == 0) {
    // Bit is not set means the offset is 0 for the debug info.
    return 0u;
  }
  // Trim off the bits above the index we want and count how many bits are set. This is how many
  // lebs we need to decode.
  size_t count = POPCOUNT(static_cast<uintptr_t>(bit_mask) << (kBitsPerIntPtrT - 1 - bit_index));
  DCHECK_GT(count, 0u);
  uint32_t current_offset = debug_info_base_;
  do {
    current_offset += DecodeUnsignedLeb128(&block);
    --count;
  } while (count > 0);
  return current_offset;
}

void CompactDexDebugInfoOffsetTable::Build(const std::vector<uint32_t>& debug_info_offsets,
                                           std::vector<uint8_t>* out_data,
                                           uint32_t* out_min_offset,
                                           uint32_t* out_table_offset) {
  DCHECK(out_data != nullptr);
  DCHECK(out_data->empty());
  // Calculate the base offset and return it.
  *out_min_offset = std::numeric_limits<uint32_t>::max();
  for (const uint32_t offset : debug_info_offsets) {
    if (offset != 0u) {
      *out_min_offset = std::min(*out_min_offset, offset);
    }
  }
  // Write the leb blocks and store the important offsets (each kElementsPerIndex elements).
  size_t block_start = 0;

  std::vector<uint32_t> offset_table;

  // Write data first then the table.
  while (block_start < debug_info_offsets.size()) {
    // Write the offset of the block for each block.
    offset_table.push_back(out_data->size());

    // Block size of up to kElementsPerIndex
    const size_t block_size = std::min(debug_info_offsets.size() - block_start, kElementsPerIndex);

    // Calculate bit mask since need to write that first.
    uint16_t bit_mask = 0u;
    for (size_t i = 0; i < block_size; ++i) {
      if (debug_info_offsets[block_start + i] != 0u) {
        bit_mask |= 1 << i;
      }
    }
    // Write bit mask.
    out_data->push_back(static_cast<uint8_t>(bit_mask >> kBitsPerByte));
    out_data->push_back(static_cast<uint8_t>(bit_mask));

    // Write debug info offsets relative to the current offset.
    uint32_t current_offset = *out_min_offset;
    for (size_t i = 0; i < block_size; ++i) {
      const uint32_t debug_info_offset = debug_info_offsets[block_start + i];
      if (debug_info_offset != 0u) {
        uint32_t delta = debug_info_offset - current_offset;
        EncodeUnsignedLeb128(out_data, delta);
        current_offset = debug_info_offset;
      }
    }

    block_start += block_size;
  }

  // Write the offset table.
  AlignmentPadVector(out_data, alignof(uint32_t));
  *out_table_offset = out_data->size();
  out_data->insert(out_data->end(),
                   reinterpret_cast<const uint8_t*>(&offset_table[0]),
                   reinterpret_cast<const uint8_t*>(&offset_table[0] + offset_table.size()));
}

}  // namespace art