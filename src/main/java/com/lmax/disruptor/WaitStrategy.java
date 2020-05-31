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
 * EventProcessor等待特定的序号可用(可以被消费)--- 序号不可用的时候，实现自己的等待策略。
 * 重点：我等待的序号什么时候可以被消费？
 * ☆当我依赖的序列的序号大于我需要消费的序号时，就可以消费了。
 *
 * 1.对于直接与生产者相连的消费者来讲，依赖的序列就是生产者的序列。
 * 2.对于其它消费者来讲，依赖的序列就是它的所有直接前驱消费者的序列。
 *
 * Strategy employed for making {@link EventProcessor}s wait on a cursor {@link Sequence}.
 */
public interface WaitStrategy
{
    /**
	 * EventProcessor等待特定的序号可用(可以被消费)。
	 *
	 * 方法有可能返回一个小于Sequence的值，依赖具体的策略实现。
	 * 这种情况的常见用途是发出超时信号，任何使用等待策略获取超时信号的事件处理器都应该显示的处理这种情况。
	 * eg:批量事件处理器显式的处理了这种情况，进行了超时通知 {@link BatchEventProcessor#notifyTimeout(long)}。
	 *
	 * 警告:如果你实现自己的等待策略，那么一定不能抛出任何声明之外的异常！ 否则可能导致严重错误，它可能导致数据/信号丢失！
	 *
     * Wait for the given sequence to be available.  It is possible for this method to return a value
     * less than the sequence number supplied depending on the implementation of the WaitStrategy.  A common
     * use for this is to signal a timeout.  Any EventProcessor that is using a WaitStrategy to get notifications
     * about message becoming available should remember to handle this case.  The {@link BatchEventProcessor} explicitly
     * handles this case and will signal a timeout if required.
     *
     * @param sequence          to be waited on. 事件处理器想消费的下一个序号。
	 *
     * @param cursor            the main sequence from ringbuffer. Wait/notify strategies will
     *                          need this as it's the only sequence that is also notified upon update.
	 *                          RingBuffer的序列（生产者的序列)
	 *                          由于当我依赖的序列的序号大于我需要消费的序号时，就可以消费了，因此生产者的序列并不是必须关注的。
	 *                          但是如果要实现生产者与消费者(或者说事件处理器)的等待通知协议时，需要cursor对象作为锁对象。
	 *
     * @param dependentSequence on which to wait.
	 *                          在这个Sequence上等待，直到它我大于期望的序号。什么的dependentSequence?
	 *                          {@link com.lmax.disruptor.ProcessingSequenceBarrier#dependentSequence}
     *
	 * @param barrier           the processor is waiting on.
	 *                          事件处理器关联(绑定)的序列屏障,一个EventProcessor只关联一个SequenceBarrier。
	 *
     * @return the sequence that is available which may be greater than the requested sequence.
	 * 			返回的是依赖的Sequence中的最大序号，可能大于我期望的序号。
	 *
     * @throws AlertException       if the status of the Disruptor has changed.
     * @throws InterruptedException if the thread is interrupted.
     * @throws TimeoutException if a timeout occurs before waiting completes (not used by some strategies)
     */
    long waitFor(long sequence, Sequence cursor, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException, TimeoutException;

    /**
	 * 如果在等待生产者生产数据时，采用了等待通知协议(wait/notify)，那么当生产者进度更新时，需要通知这些被阻塞的事件处理器
	 *
	 * Implementations should signal the waiting {@link EventProcessor}s that the cursor has advanced.
     */
    void signalAllWhenBlocking();
}
