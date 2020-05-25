/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

/**
 * RingBuffer使用{@link #newInstance()}预创建对象预填充共享数据区。
 * 创建的实际是数据的载体对象，载体对象是反复使用的，会预分配，因此存在短时间的内存泄漏风险，
 * 因此讲道理最好每次处理完之后将数据进行清理，以帮助垃圾回收。
 *
 * Called by the {@link RingBuffer} to pre-populate all the events to fill the RingBuffer.
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public interface EventFactory<T>
{
    /*
     * Implementations should instantiate an event object, with all memory already allocated where possible.
     */
    T newInstance();
}