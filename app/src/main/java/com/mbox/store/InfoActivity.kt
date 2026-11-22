package com.mbox.store

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mbox.store.databinding.ActivityInfoBinding

/** A simple title + long-text page (About, legal and explainer screens). */
class InfoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"

        fun open(context: Context, title: String, body: String) {
            context.startActivity(
                Intent(context, InfoActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_BODY, body)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.infoTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.infoBody.text = intent.getStringExtra(EXTRA_BODY).orEmpty()
        binding.infoBack.setOnClickListener { finish() }
    }
}
