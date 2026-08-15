package io.github.kr9ly.daybook.test

import io.github.kr9ly.daybook.kv.Daybook
import io.github.kr9ly.daybook.kv.DaybookSchema

public actual class TestDaybook actual constructor(packageName: String) {

    private val state = TestDaybookState()

    public actual fun getDaybook(schema: DaybookSchema, multiProcess: Boolean): Daybook =
        state.getDaybook(schema, multiProcess)

    public actual fun commits(name: String): List<RecordedCommit> = state.commits(name)

    public actual fun failNextWrite(name: String) {
        state.failNextWrite(name)
    }
}
