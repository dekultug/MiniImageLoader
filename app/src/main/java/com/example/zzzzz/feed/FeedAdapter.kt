package com.example.zzzzz.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.zzzzz.MiniImageLoader
import com.example.zzzzz.R
import com.example.zzzzz.databinding.ItemFeedBinding
import com.example.zzzzz.mock.FakeServerConfig
import com.example.zzzzz.mock.FeedItem

/**
 * @param cellWidthPx bề rộng ô ảnh (px) — dùng để xin server đúng `w`, không tải ảnh to hơn ô.
 */
class FeedAdapter(
    private val cellWidthPx: Int,
) : ListAdapter<FeedItem, FeedAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        // Huỷ request đang chạy + trả bitmap về pool khi ô bị tái dùng
        Glide.with(holder.binding.image).clear(holder.binding.image)
    }

    inner class ViewHolder(val binding: ItemFeedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FeedItem) {
            binding.title.text = item.title
            // into(view) tự huỷ request cũ của ImageView này → không bị nhầm ô khi cuộn nhanh

            MiniImageLoader.loadAndShowImage(
                context = binding.root.context,
                url = sizedUrl(item.imageUrl, cellWidthPx),
                target = binding.image,
                placeHolder = R.color.feed_placeholder
            )
//            Glide.with(binding.image)
//                .load(sizedUrl(item.imageUrl, cellWidthPx))
//                .placeholder(R.color.feed_placeholder)
//                .error(R.color.feed_error)
//                .into(binding.image)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FeedItem>() {
            override fun areItemsTheSame(oldItem: FeedItem, newItem: FeedItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: FeedItem, newItem: FeedItem) = oldItem == newItem
        }

        /** Chọn w nhỏ nhất server có mà vẫn ≥ bề rộng ô; ô to hơn 1080 thì lấy 1080. */
        fun sizedUrl(baseUrl: String, cellWidthPx: Int): String {
            val w = FakeServerConfig.ALLOWED_WIDTHS.firstOrNull { it >= cellWidthPx }
                ?: FakeServerConfig.ALLOWED_WIDTHS.last()
            return "$baseUrl?w=$w"
        }
    }
}
