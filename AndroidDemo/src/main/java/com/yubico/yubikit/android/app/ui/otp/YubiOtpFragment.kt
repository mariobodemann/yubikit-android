package com.yubico.yubikit.android.app.ui.otp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.yubico.yubikit.android.app.MainViewModel
import com.yubico.yubikit.android.app.R
import com.yubico.yubikit.android.ui.OtpActivity
import com.yubico.yubikit.otp.Slot
import com.yubico.yubikit.keyboard.Modhex
import com.yubico.yubikit.utils.RandomUtils
import kotlinx.android.synthetic.main.fragment_otp_yubi_otp.*
import org.bouncycastle.util.encoders.Hex

private const val REQUEST_OTP_CODE = 1

class YubiOtpFragment : Fragment() {
    private val activityViewModel: MainViewModel by activityViewModels()
    private val viewModel: OtpViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_otp_yubi_otp, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        text_layout_public_id.setEndIconOnClickListener {
            edit_text_public_id.setText(Modhex.encode(RandomUtils.getRandomBytes(6)))
        }
        edit_text_public_id.setText(Modhex.encode(RandomUtils.getRandomBytes(6)))

        text_layout_private_id.setEndIconOnClickListener {
            edit_text_private_id.setText(String(Hex.encode(RandomUtils.getRandomBytes(6))))
        }
        edit_text_private_id.setText(String(Hex.encode(RandomUtils.getRandomBytes(6))))

        text_layout_key.setEndIconOnClickListener {
            edit_text_key.setText(String(Hex.encode(RandomUtils.getRandomBytes(16))))
        }
        edit_text_key.setText(String(Hex.encode(RandomUtils.getRandomBytes(16))))

        btn_save.setOnClickListener {
            try {
                val publicId = Modhex.decode(edit_text_public_id.text.toString())
                val privateId = Hex.decode(edit_text_private_id.text.toString())
                val key = Hex.decode(edit_text_key.text.toString())
                val slot = when (slot_radio.checkedRadioButtonId) {
                    R.id.radio_slot_1 -> Slot.ONE
                    R.id.radio_slot_2 -> Slot.TWO
                    else -> throw IllegalStateException("No slot selected")
                }

                viewModel.pendingAction.value = {
                    setOtpKey(slot, publicId, privateId, key)
                    "Slot $slot programmed"
                }
            } catch (e: Exception) {
                viewModel.postResult(Result.failure(e))
            }
        }

        btn_request_otp.setOnClickListener {
            activityViewModel.setYubiKeyListenerEnabled(false)
            viewModel.releaseYubiKey()
            startActivityForResult(Intent(context, OtpActivity::class.java), REQUEST_OTP_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_OTP_CODE) {
            activityViewModel.setYubiKeyListenerEnabled(true)
            if (resultCode == Activity.RESULT_OK) {
                data?.getStringExtra(OtpActivity.EXTRA_OTP)?.let {
                    viewModel.postResult(Result.success("Read OTP: $it"))
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                viewModel.postResult(Result.success("Cancelled by user"))
            }
        }
    }
}