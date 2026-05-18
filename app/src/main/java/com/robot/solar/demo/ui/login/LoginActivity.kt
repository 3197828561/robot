package com.robot.solar.demo.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.robot.solar.demo.databinding.ActivityLoginBinding
import com.robot.solar.demo.ui.main.MainActivity
import com.robot.solar.demo.viewmodel.LoginViewModel

/**
 * 登录界面：ViewBinding + ViewModel，演示固定账号与记住密码能力
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initFormFromPrefs()
        observeEvents()
        binding.btnLogin.setOnClickListener { submit() }
    }

    private fun initFormFromPrefs() {
        binding.cbRemember.isChecked = viewModel.isRememberPassword()
        binding.cbAutoLogin.isChecked = viewModel.isAutoLoginEnabled()
        if (viewModel.isRememberPassword()) {
            binding.etUsername.setText(viewModel.getSavedUsername())
            binding.etPassword.setText(viewModel.getSavedPassword())
        }
    }

    private fun observeEvents() {
        viewModel.navigateHome.observe(this) { go ->
            if (go == true) {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                finish()
                viewModel.consumeNavigateHome()
            }
        }
        viewModel.toastMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.consumeToast()
            }
        }
    }

    private fun submit() {
        val username = binding.etUsername.text?.toString().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        viewModel.login(
            username = username,
            password = password,
            remember = binding.cbRemember.isChecked,
            autoLogin = binding.cbAutoLogin.isChecked
        )
    }
}
