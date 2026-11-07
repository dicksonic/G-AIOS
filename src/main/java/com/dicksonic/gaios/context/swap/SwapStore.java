package com.dicksonic.gaios.context.swap;

import com.dicksonic.gaios.context.swap.event.SwapEvent;

/**
 * 换出存储：把压缩时滑出窗口的原文持久化，服务审计回放（D7）。
 *
 * 实现约束：spill 失败只允许打日志、不允许抛异常——审计层不能搞挂主流程。
 */
public interface SwapStore {

    void spill(SwapEvent event);
}