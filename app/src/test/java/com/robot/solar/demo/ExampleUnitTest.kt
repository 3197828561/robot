package com.robot.solar.demo

import com.robot.solar.demo.repository.LoginRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地 JVM 单元测试（示例）：校验演示账号常量
 */
class ExampleUnitTest {

    @Test
    fun demoAccount_isExpected() {
        assertEquals("admin", LoginRepository.DEMO_USER)
        assertEquals("123456", LoginRepository.DEMO_PASS)
    }
}
