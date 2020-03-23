package com.lmax.disruptor;

/**
 * 批量事件处理通知(感知)
 * 如果你的EventHandler实现了该接口，那么当批处理开始时，你会收到通知
 * Implement this interface in your {@link EventHandler} to be notified when a thread for the
 * {@link BatchEventProcessor} start process events.
 */
public interface BatchStartAware
{
	/**
	 * 批处理开始
	 * @param batchSize 本批次事件个数
	 */
    void onBatchStart(long batchSize);
}
