package com.robot.solar

import com.robot.solar.viewmodel.LoginErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class LoginErrorMessageTest {
    @Test
    fun networkFailureUsesUserFacingMessage() {
        assertEquals(
            "无法连接服务器，请检查网络后重试",
            LoginErrorMessage.from(IOException("socket details"))
        )
    }

    @Test
    fun internalFailureDoesNotExposeExceptionText() {
        assertEquals(
            "登录服务暂时不可用，请稍后重试",
            LoginErrorMessage.from(IllegalStateException("internal details"))
        )
    }
}
