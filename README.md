# MiniImageLoader

## 4 ý chính

Gói gọn: **đúng ô (1), không làm việc thừa (2, 3), không tốn bộ nhớ (4).**

### 1. Check request của từng ImageView — chống load nhầm ô

Mỗi lần load tạo **một object `ImageRequest` mới** và cất vào tag của ImageView (`R.id.mini_image_request`). Tải xong chỉ gán ảnh nếu tag **vẫn `===` đúng object đó** — kiểm tra trên main thread. Kèm theo: gán placeholder ngay khi load để ô tái dùng không hiện ảnh cũ.

```kotlin
val request = ImageRequest(url, target, ...)          // object mới mỗi lần
target.setTag(R.id.mini_image_request, request)
// ...tải xong:
target.post {
    if (target.getTag(R.id.mini_image_request) !== request) return@post   // ô đã đổi item → bỏ
    target.setImageBitmap(bitmap)
}
```

So object chứ không so URL: một ô có thể `#20 → #45 → #20`, hai lần `#20` là hai request khác nhau.

### 2. Cancel request khi ô bị tái dùng

`MiniImageLoader.cancel(imageView)` được gọi trong `onViewRecycled`, và tự gọi khi ô bind sang URL khác. Request mạng chỉ thật sự bị huỷ khi **không còn ô nào chờ URL đó** — ô trái cuộn đi không làm hỏng ô phải cùng ảnh.

### 3. Dùng lại nếu cùng resource

Ba mức, từ nhanh đến chậm:

| Ảnh đang ở đâu | Làm gì |
|---|---|
| **Đang được tải** | Ô sau chỉ đăng ký chờ chung lượt tải (map `inFlight: URL → DownloadJob`) → nhiều ô cùng URL = 1 request mạng |
| **Có trong RAM** | Gán ngay (≈ 0 ms) |
| **Có trên đĩa** | Đọc file + decode, không gọi mạng |

Ô cùng cỡ chỉ decode 1 lần. `inFlight` không phải một tầng cache — nó không lưu ảnh, chỉ ghi "URL này đang được tải".

### 4. Quản lý bộ nhớ — cache 2 tầng

| | Tầng 1: RAM | Tầng 2: Đĩa |
|---|---|---|
| Lưu dạng | Bitmap đã decode, đúng cỡ ô (~2 MB / ảnh 720 px) | File JPEG nén từ server (~130 KB / ảnh) |
| Giới hạn | `LruCache` tính theo **byte**, ≤ 1/8 heap | **50 MB** |
| Dọn khi đầy | LruCache đẩy ảnh lâu không dùng; `onTrimMemory` → xoá hết | Xoá file có **`lastModified` cũ nhất** (mỗi lần đọc trúng cache đều cập nhật mốc này → "lâu không dùng nhất") |
| Tránh được | decode lại | tải lại qua mạng |
| Tắt app | mất | còn |

Thêm vào đó: **decode đúng cỡ ô** bằng `inSampleSize` — ảnh 4000 px cho ô 500 px từ ~48 MB còn ~0,75 MB.

---

## Có gì mới so với bản đầu tiên

Bản đầu tiên tải và hiển thị được ảnh, nhưng khi cuộn nhanh thì lộ nhiều lỗi. Bản này sửa toàn bộ, đã kiểm lại trên emulator với cùng kịch bản:

| Lỗi ở bản đầu | Biểu hiện | Đã sửa thế nào |
|---|---|---|
| **Load nhầm ô** | Cuộn nhanh rồi dừng: ô "Ảnh #22" hiện ảnh `#10`, "#23" hiện `#11`, "#24" hiện `#12`, "#25" hiện `#1`/`#14`. Có trường hợp sai luôn không tự đúng lại | Mỗi ImageView giữ request hiện tại trong tag; ảnh tải xong chỉ được gán nếu ô vẫn đang chờ đúng request đó |
| **Ô tái dùng hiện ảnh cũ** | Trong lúc chờ ảnh mới, ô vẫn hiện ảnh của item trước (vd "Ảnh #20" hiện `#7`) | Gán placeholder ngay khi bắt đầu load |
| **Không huỷ khi cuộn qua** | Ô đã cuộn khỏi màn hình vẫn tải tiếp: 32 request cho 27 ảnh sau 1 lần cuộn | Huỷ request trong `onViewRecycled` (trước đó vẫn gọi nhầm `Glide.clear`, không có tác dụng) |
| **Cùng URL tải nhiều lần** | Cặp ô `#5 #5` gọi mạng 2 lần (`dup=1`) | Gộp request: nhiều ô cùng URL dùng chung 1 lượt tải → `dup=0` |
| **Crash khi gán ảnh** | `CalledFromWrongThreadException` vì `setImageBitmap` chạy trên thread nền | Decode trên thread nền, chỉ gán ảnh trên main thread |
| **Decode sai cỡ** | Đọc cỡ ImageView trên thread nền, có thể = 0 → decode nguyên ảnh | Đọc cỡ ô trên main trước khi tải; key cache RAM gồm cả cỡ (`url@WxH`) |
| **Thiếu `onTrimMemory`** | Hệ thống báo thiếu RAM nhưng cache không giải phóng | Đăng ký `ComponentCallbacks2` → xoá cache RAM |
| **Cache đĩa phình mãi** | Không giới hạn dung lượng, không dọn file `.tmp` ghi dở | Giới hạn 50 MB, xoá file lâu không dùng nhất; dọn `.tmp` sót lại khi khởi động |
| **Lỗi decode làm ô xám mãi** | Chỉ bắt `IOException`, lỗi khác làm crash thread | Bắt cả `OutOfMemoryError`, hiện ảnh lỗi riêng |
| **Quá nhiều thread decode** | Cuộn nhanh qua ảnh đã cache sinh hàng loạt thread decode | Pool decode cố định 2–4 thread |

Kết quả sau khi sửa: mở app `requests=7 unique=7 dup=0`; cuộn nhanh rồi dừng, mọi ô đúng ảnh; cuộn về đầu, ảnh hiện ngay từ cache RAM. Chi tiết từng điểm: [docs/MiniImageLoader.md](docs/MiniImageLoader.md#5-bản-cũ-và-bản-mới).

---

Tự viết một image loader nhỏ cho Android (~300 dòng) để hiểu bộ nhớ Bitmap, cache 2 tầng và huỷ request theo ViewHolder — thay vì để Glide/Coil làm hộ.

Ứng dụng demo là màn **Feed lưới 2 cột** tải ảnh từ một **server giả chạy ngay trong app** (MockWebServer), kèm dòng số đo phía server để kiểm loader có làm đúng không.

> Giải thích chi tiết thiết kế và những gì đã cải thiện: [docs/MiniImageLoader.md](docs/MiniImageLoader.md)

## Yêu cầu bài toán

| Yêu cầu | Cách làm |
|---|---|
| `load(url, imageView)`, tự huỷ khi ViewHolder bị tái dùng | `MiniImageLoader.loadAndShowImage(...)` + `MiniImageLoader.cancel(imageView)` trong `onViewRecycled` |
| Decode đúng cỡ ô (`inSampleSize`) | `BitmapDecoder`: đọc kích thước bằng `inJustDecodeBounds`, decode với luỹ thừa của 2 lớn nhất vẫn ≥ cỡ ô |
| Cache RAM: `LruCache` tính theo byte, ≤ 1/8 heap | `LruCache<String, Bitmap>` với `sizeOf = allocationByteCount`, key `url@WxH` |
| Cache đĩa: tối đa 50 MB, xoá file lâu không dùng nhất | `UrlDownloader(maxCacheBytes = 50 MB)`, xoá theo `lastModified` cũ nhất |
| Nhiều ô cùng URL → 1 request mạng | Map `inFlight: URL → DownloadJob`, các ô sau chỉ đăng ký chờ |
| `onTrimMemory` → xoá cache RAM | `ComponentCallbacks2` đăng ký trong `init` |
| Ghi file cache an toàn | Ghi `.tmp` rồi `renameTo`; dọn `.tmp` sót lại khi khởi tạo |

## Cấu trúc

```
app/src/main/java/com/example/zzzzz/
├── MiniImageLoader.kt          # Loader: cache RAM, gộp request, huỷ theo ImageView, chống nhầm ô
├── loader/
│   ├── UrlDownloader.kt        # Tải URL → file: validate, cache đĩa (GET/DOWNLOAD), .tmp → rename, giới hạn dung lượng
│   └── BitmapDecoder.kt        # File → Bitmap đúng cỡ ô (inSampleSize)
├── feed/
│   ├── FeedAdapter.kt          # Lưới 2 cột, chọn w theo cỡ ô
│   └── FeedViewModel.kt        # Phân trang feed
├── mock/                       # Server giả (MockWebServer) + số đo phía server
└── MainActivity.kt
```

Luồng một lần load:

```
RAM (LruCache) ──trượt──▶ đĩa (cacheDir/image_cache) ──trượt──▶ mạng
                                                                  │
ImageView ◀── main thread ◀── RAM ◀── decode đúng cỡ ô ◀── ghi .tmp → rename
```

## Server giả

Chạy trong process app tại `http://127.0.0.1:8765`, tự start trong `ZZZApplication`.

| Endpoint | Trả về |
|---|---|
| `GET /v1/images/{id}?w=160\|320\|480\|720\|1080` | JPEG vuông rộng đúng `w`, delay ngẫu nhiên 0,5–2 s. Ảnh có số `#id`, viền màu theo id và dải `w=…` để nhìn là biết đúng ô, đúng cỡ |
| `GET /v1/feed?page=0&size=30` | JSON 300 ô. Cứ 6 ô thì ô thứ 6 trùng ảnh ô liền trước (kiểm gộp request); id 1..200 rồi quay vòng (kiểm cache khi cuộn xa) |

Chỉnh delay, tỉ lệ lỗi… trong `mock/FakeServerConfig.kt`.

Dòng số đo trên đầu màn Feed (`FakeServerStats`):

```
requests=7 unique=7 dup=0 bytes=921KB inFlight=0 peak=5
```

- `dup` = số lần một URL bị tải lại. Cuộn bình thường phải là **0**.
- Mở lại app: `requests=0` — ảnh lấy từ cache đĩa.

## Chạy

Yêu cầu: Android Studio (AGP 9), Gradle daemon JDK 21 (tự tải qua toolchain), minSdk 24.

Project dùng một thư viện từ GitHub Packages, cần khai báo thông tin đăng nhập ở **`~/.gradle/gradle.properties`** (không để trong project):

```properties
gpr.user=<github username>
gpr.key=<github token có quyền read:packages>
```

Build và cài:

```bash
./gradlew :app:installDebug
```

Unit test (downloader, decoder):

```bash
./gradlew :app:testDebugUnitTest --tests 'com.example.zzzzz.loader.*'
```

## Tự kiểm tra loader

1. Mở app, chưa chạm gì: cặp ô `#5 #5` chỉ tạo 1 request (`dup=0`).
2. Cuộn nhanh rồi dừng: không ô nào hiện số `#` khác tiêu đề của nó, không ô nào xám mãi.
3. Cuộn về đầu: ảnh hiện ngay (cache RAM).
4. Tắt app mở lại: `requests=0` (cache đĩa).

Cuộn thật nhanh xuống rồi ngược lên thì `dup` có thể tăng: ảnh của ô bị lướt qua đã bị huỷ giữa chừng nên phải tải lại — đánh đổi có chủ ý, Glide cũng làm vậy.

## Chưa làm

- Công tắc "Loader: Mini / Coil" ở Màn Dev để so RAM và độ giật.
- Unit test cho `MiniImageLoader` (hiện chỉ kiểm bằng tay trên emulator).
