package ali.paf.contacts.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import ali.paf.contacts.R
import ali.paf.contacts.databinding.ActivityAppInfoBinding

class AppInfoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAppInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.tvVersion.text = getString(
            R.string.app_info_version,
            packageManager.getPackageInfo(packageName, 0).versionName
        )
        binding.btnUserGuide.setOnClickListener { openUserGuide() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun openUserGuide() {
        val guideFile = File(cacheDir, "air_contacts_sop.pdf")
        if (!guideFile.exists()) {
            resources.openRawResource(R.raw.air_contacts_sop).use { input ->
                guideFile.outputStream().use(input::copyTo)
            }
        }

        val guideUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            guideFile
        )
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(guideUri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
