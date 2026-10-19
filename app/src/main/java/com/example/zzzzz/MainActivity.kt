package com.example.zzzzz

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zzzzz.databinding.ActivityMainBinding
import com.example.zzzzz.feed.FeedAdapter
import com.example.zzzzz.feed.FeedViewModel
import com.example.zzzzz.mock.FakeServerStats
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: FeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val adapter = FeedAdapter(cellWidthPx = estimateCellWidthPx())
        binding.feed.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.feed.adapter = adapter
        binding.feed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as GridLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= lm.itemCount - LOAD_MORE_THRESHOLD) {
                    viewModel.loadNextPage()
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.items)
                        binding.progress.visibility = if (state.isLoading) View.VISIBLE else View.INVISIBLE
                        state.error?.let { showError(it) }
                    }
                }
                launch {
                    while (true) {
                        binding.stats.text = FakeServerStats.summary()
                        delay(STATS_REFRESH_MS)
                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        viewModel.consumeError()
        Snackbar.make(binding.root, getString(R.string.feed_load_error, message), Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.retry) { viewModel.loadNextPage() }
            .show()
    }

    /** Bề rộng ô ảnh = (màn hình − padding RecyclerView) / số cột − padding item hai bên. */
    private fun estimateCellWidthPx(): Int {
        val density = resources.displayMetrics.density
        val rvPadding = (4 * 2 * density).toInt()
        val itemPadding = (4 * 2 * density).toInt()
        return (resources.displayMetrics.widthPixels - rvPadding) / SPAN_COUNT - itemPadding
    }

    private companion object {
        const val SPAN_COUNT = 2
        const val LOAD_MORE_THRESHOLD = 6
        const val STATS_REFRESH_MS = 500L
    }
}
