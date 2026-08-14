package io.github.kr9ly.daybook.test

import io.github.kr9ly.daybook.kv.Daybook

public actual class TestDaybook actual constructor(packageName: String) {

    private val state = TestDaybookState(packageName)

    public actual fun getDaybook(name: String, multiProcess: Boolean): Daybook =
        state.getDaybook(name, multiProcess)

    public actual fun getDefaultDaybook(multiProcess: Boolean): Daybook =
        state.getDaybook(state.defaultName(), multiProcess)

    public actual fun commits(name: String): List<RecordedCommit> = state.commits(name)

    public actual fun failNextWrite(name: String) {
        state.failNextWrite(name)
    }
}
