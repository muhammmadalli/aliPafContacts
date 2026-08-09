package ali.paf.contacts.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ali.paf.contacts.databinding.ActivityAppInfoBinding

class AppInfoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAppInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.tvVersion.text = getString(
            ali.paf.contacts.R.string.app_info_version,
            packageManager.getPackageInfo(packageName, 0).versionName
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
