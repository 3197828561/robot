package com.robot.solar.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.robot.solar.data.session.RememberedLoginStore
import com.robot.solar.databinding.ActivityLoginBinding
import com.robot.solar.ui.device.DeviceListActivity
import com.robot.solar.viewmodel.LoginViewModel

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()
    private lateinit var rememberedLoginStore: RememberedLoginStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rememberedLoginStore = RememberedLoginStore(this)
        rememberedLoginStore.load()?.let { remembered ->
            binding.etUsername.setText(remembered.email)
            binding.etPassword.setText(remembered.password)
            binding.cbRemember.isChecked = true
        }
        binding.cbRemember.visibility = android.view.View.VISIBLE
        binding.cbRemember.text = "记住登录信息"
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
                val email = binding.etUsername.text?.toString().orEmpty().trim()
                val password = binding.etPassword.text?.toString().orEmpty()
                if (binding.cbRemember.isChecked) {
                    rememberedLoginStore.save(email, password)
                } else {
                    rememberedLoginStore.clear()
                }
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
