package com.robot.solar.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.robot.solar.databinding.ActivityLoginBinding
import com.robot.solar.ui.device.DeviceListActivity
import com.robot.solar.viewmodel.LoginViewModel

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cbRemember.visibility = android.view.View.GONE
        binding.cbAutoLogin.visibility = android.view.View.GONE
        binding.tilUsername.hint = getString(com.robot.solar.R.string.hint_email)

        binding.btnLogin.setOnClickListener {
            viewModel.login(
                binding.etUsername.text?.toString().orEmpty(),
                binding.etPassword.text?.toString().orEmpty()
            )
        }

        viewModel.navigateNext.observe(this) { go ->
            if (go == true) {
                startActivity(Intent(this, DeviceListActivity::class.java))
                finish()
                viewModel.consumeNavigate()
            }
        }
        viewModel.toastMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.consumeToast()
            }
        }
    }
}
