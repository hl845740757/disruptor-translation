/*
 * Copyright 2012 LMAX Ltd.
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
 * 该接口是面向消费者的，消费者用于感知生产者的进度。
 *
 * Implementors of this interface must provide a single long value
 * that represents their current cursor value.  Used during dynamic
 * add/remove of Sequences from a
 * {@link SequenceGroups#addSequences(Object, java.util.concurrent.atomic.AtomicReferenceFieldUpdater, Cursored, Sequence...)}.
 */
public interface Cursored
{
    /**
	 * 获取当前的游标值
     * Get the current cursor value.
     *
     * @return current cursor value
     */
    long getCursor();
}
