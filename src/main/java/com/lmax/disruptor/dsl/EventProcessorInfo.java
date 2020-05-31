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
package com.lmax.disruptor.dsl;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;

import java.util.concurrent.Executor;

/**
 * 单事件处理器消费者信息。
 * (单个EventProcessor处理所有的事件的消费者)
 *
 * <p>Wrapper class to tie together a particular event processing stage</p>
 * <p>
 * <p>Tracks the event processor instance, the event handler instance, and sequence barrier which the stage is attached to.</p>
 *
 * @param <T> the type of the configured {@link EventHandler}
 */
class EventProcessorInfo<T> implements ConsumerInfo
{
	/**
	 * 事件处理器，该EventProcessor用于处理所有的事件，目前其实就是{@link com.lmax.disruptor.BatchEventProcessor}。
	 * {@link com.lmax.disruptor.BatchEventProcessor}
	 * {@link com.lmax.disruptor.WorkProcessor}
	 */
    private final EventProcessor eventprocessor;
	/**
	 * 事件处理方法
	 */
    private final EventHandler<? super T> handler;
	/**
	 * 消费者的序列屏障
	 */
    private final SequenceBarrier barrier;
	/**
	 * 是否是消费链的末端消费者(没有后继消费者)
	 */
    private boolean endOfChain = true;

    EventProcessorInfo(
        final EventProcessor eventprocessor, final EventHandler<? super T> handler, final SequenceBarrier barrier)
    {
        this.eventprocessor = eventprocessor;
        this.handler = handler;
        this.barrier = barrier;
    }

    public EventProcessor getEventProcessor()
    {
        return eventprocessor;
    }

    @Override
    public Sequence[] getSequences()
    {
        return new Sequence[]{eventprocessor.getSequence()};
    }

    public EventHandler<? super T> getHandler()
    {
        return handler;
    }

    @Override
    public SequenceBarrier getBarrier()
    {
        return barrier;
    }

    @Override
    public boolean isEndOfChain()
    {
        return endOfChain;
    }

    @Override
    public void start(final Executor executor)
    {
        executor.execute(eventprocessor);
    }

    @Override
    public void halt()
    {
        eventprocessor.halt();
    }

    /**
     *
     */
    @Override
    public void markAsUsedInBarrier()
    {
        endOfChain = false;
    }

    @Override
    public boolean isRunning()
    {
        return eventprocessor.isRunning();
    }
}
