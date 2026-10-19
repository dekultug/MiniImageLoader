# MiniImageLoader — thiết kế và những gì đã cải thiện

Tài liệu tổng hợp quá trình viết và sửa `MiniImageLoader`: các khái niệm nền, cách chống nhầm ô, cách gộp request, và so sánh bản cũ với bản mới (đo trên emulator).

## 1. File ảnh và Bitmap khác nhau thế nào

- **File ảnh** (JPEG/PNG/WebP): dữ liệu **đã nén**, phải decode mới hiển thị được.
- **`android.graphics.Bitmap`**: vùng nhớ chứa **từng pixel đã giải nén**, vẽ được ngay. Không phải file.

Với ảnh 720×720 của server giả:

| | Dung lượng |
|---|---|
| File JPEG | ~130 KB |
| Bitmap `ARGB_8888` | 720 × 720 × 4 ≈ **2 MB** (gấp ~15 lần) |

Vì vậy cần **cache 2 tầng**, mỗi tầng lưu một dạng:

- **Đĩa lưu file nén** (bytes gốc từ mạng) → tránh tải lại. 50 MB chứa được ~400 ảnh 720 px.
- **RAM lưu Bitmap đã decode đúng cỡ ô** → tránh decode lại. 1/8 heap chỉ chứa vài chục Bitmap, nên phải giới hạn theo **byte** (`allocationByteCount`), không theo số lượng.

`inSampleSize` là nút tiết kiệm RAM lớn nhất: ảnh 4000×3000 cho ô 500 px decode full tốn ~48 MB, với `inSampleSize = 8` còn ~0,75 MB — giảm ngay lúc decode chứ không decode full rồi thu nhỏ.

## 2. Tên file cache: `sha256(url)`

```kotlin
private fun hashOf(url: String): String = url.encodeUtf8().sha256().hex()
```

`encodeUtf8()` → bytes, `sha256()` → 32 byte, `hex()` → chuỗi 64 ký tự `0-9a-f`.

- URL có `/ ? : &` — không hợp lệ trong tên file; hex thì luôn hợp lệ.
- URL có thể dài quá giới hạn 255 byte của tên file; hash luôn 64 ký tự.
- Cùng URL → cùng tên → tra được cache đĩa.
- Khác URL → gần như chắc chắn khác tên (`hashCode()` 32 bit thì dễ trùng → 2 ảnh ghi đè nhau).

`…/12?w=720` và `…/12?w=480` ra 2 hash khác hẳn nhau — **đúng ý**, vì đó là 2 file JPEG khác nhau do server trả về. Trong Feed mọi ô cùng cỡ nên luôn xin cùng `w`, không bị lưu trùng.

> Muốn tìm **ảnh tương tự** thì SHA-256 không dùng được (khác 1 byte là khác hoàn toàn). Cần perceptual hash (dHash/pHash + khoảng cách Hamming) cho ảnh gần trùng, hoặc embedding ML cho ảnh cùng nội dung.

## 3. Chống nhầm ô: tag trên ImageView

Mọi ImageView dùng chung key `R.id.mini_image_request`, nhưng **mỗi View có kho tag riêng** — key chỉ là tên ngăn, thứ cất trong ngăn là của từng View:

```
ImageView ô A:  [mini_image_request] → ImageRequest(#20)   ← object 1
ImageView ô B:  [mini_image_request] → ImageRequest(#21)   ← object 2
```

- `setTag(key, value)` cần key là resource id (khai báo trong `res/values/ids.xml`), tránh đụng với `setTag(value)` mà thư viện khác có thể dùng.
- Ngăn của mỗi ô luôn chỉ giữ **request mới nhất**. Kết quả về muộn mà không còn khớp (`===`) thì bỏ:

```
1. bind #20 → tag(A) = R1(#20) → bắt đầu tải
2. ô A bị tái dùng, bind #45 → cancel(R1) → tag(A) = R2(#45)
3. #20 tải xong → tag(A) === R1 ?  → đang là R2 → KHÔNG gán ✅
4. #45 tải xong → tag(A) === R2 ?  → đúng → gán ✅
```

So `===` thay vì so URL: ô A có thể `#20 → #45 → #20`, tạo 2 request khác nhau cho cùng URL — chỉ so object mới phân biệt được.

## 4. Gộp request: `inFlight`

`inFlight` là **danh sách URL đang được tải**. Trước khi gọi mạng: có ai đang tải URL này chưa? Có rồi thì đứng chờ chung.

```kotlin
inFlight: HashMap<String, DownloadJob>      // URL → lượt tải đang chạy

DownloadJob(url)
    waiters: List<ImageRequest>   // các ô đang chờ URL này
    handle: Cancellable           // để huỷ request mạng
```

```
ô trái  bind #5 → inFlight chưa có #5 → tạo DownloadJob, gọi mạng ──→ xong ─┬→ hiện ô trái
ô phải  bind #5 → inFlight đã có #5  → chỉ đăng ký chờ ─────────────────────┘→ hiện ô phải
```

Vòng đời một job:

1. **Tham gia hoặc tạo mới** (`start`, main thread).
2. **Một ô rời đi** (`cancel`): bỏ ô khỏi `waiters`. Chỉ khi **không còn ai chờ** mới huỷ request mạng — ô trái cuộn đi không được làm hỏng ô phải.
3. **Tải xong** (`onSuccess`, thread decode): gỡ job khỏi map, decode 1 lần cho mỗi cỡ ô, gửi bitmap cho từng ô còn chờ.
4. **Gỡ khỏi map ngay khi xong**: ô bind sau sẽ trúng cache RAM/đĩa. Giữ job lại thì ô mới đăng ký chờ một job đã xong và không bao giờ được báo.

`synchronized(lock)` vì main thread (thêm/bớt chờ) và thread decode (gỡ job, lấy danh sách chờ) cùng đụng vào map. Không khoá thì có thể: main vừa thấy "#5 đang tải", thread decode gỡ job và gửi kết quả, rồi main mới đăng ký → ô bị bỏ sót, xám mãi.

Ý tưởng giống `singleflight` của Go, và Coil cũng gộp request tương tự.

## 5. Bản cũ và bản mới

### Đo trên emulator, cùng kịch bản

| | Bản cũ | Bản mới |
|---|---|---|
| Mở app (8 ô, có cặp `#5 #5`) | `dup=1` — cặp #5 tải 2 lần | `requests=7 unique=7 dup=0` |
| Cuộn nhanh, ~4 s sau | "Ảnh #22" hiện `#10`, "#23" hiện `#11`, "#24" hiện `#12`, "#25" hiện `#1` và `#14` | Mọi ô #20–#25 đúng ảnh |
| Một lần cuộn nhanh | 32 request / 27 URL (`dup=5`) — ô đã cuộn qua vẫn tải tiếp | Ô cuộn qua bị huỷ, cuộn xuống giữ `dup=0` |
| Cuộn ngược về đầu | tải/decode lại | #1–#7 hiện ngay từ cache RAM |

### Từng điểm

| # | Vấn đề | Bản cũ | Bản mới |
|---|---|---|---|
| 1 | Nhầm ô | Gán bitmap không kiểm tra ô còn chờ URL đó không | Tag request trên ImageView, chỉ gán khi tag vẫn đúng request (`===`) |
| 2 | Ảnh cũ khi tái dùng ô | Ô giữ ảnh item trước tới khi ảnh mới về | Gán placeholder ngay lúc load |
| 3 | Huỷ theo ViewHolder | `Cancellable` bị bỏ; `onViewRecycled` gọi `Glide.clear` (vô tác dụng) | `MiniImageLoader.cancel(imageView)` trong `onViewRecycled`; load URL mới tự huỷ request cũ |
| 4 | Gộp request | 2 ô cùng URL → 2 request | `inFlight: URL → DownloadJob`; 1 ô huỷ không huỷ request chung |
| 5 | Decode đúng cỡ ô | Đọc `target.width` trên thread nền, có thể = 0 → decode full | Đọc cỡ trên main trước khi tải; cùng cỡ chỉ decode 1 lần |
| 6 | Key cache RAM | `url` | `url@WxH` |
| 7 | `onTrimMemory` | Không có | `ComponentCallbacks2` → xoá cache RAM; thêm `clearMemory()` |
| 8 | Cache đĩa 50 MB | Thư mục phình mãi | Vượt 50 MB → xoá file `lastModified` cũ nhất (GET trúng cache cập nhật mốc này) |
| 9 | File `.tmp` sót | App bị kill lúc ghi → `.tmp` nằm mãi | Khởi tạo dọn `.tmp` cũ hơn 10 s |
| 10 | Thread | `setImageBitmap` trên thread nền → `CalledFromWrongThreadException` | Decode trên thread nền, đụng View trên main |
| 11 | Số thread decode | Pool OkHttp không giới hạn | Pool cố định 2–4 thread |
| 12 | Lỗi decode | Chỉ bắt `IOException`; lỗi khác crash thread, ô xám mãi | Bắt cả `OutOfMemoryError` (kèm xoá cache RAM), hiện ảnh lỗi |
| 13 | Ảnh lỗi | Chung màu với đang chờ | `errorPlaceHolder` riêng (`feed_error`) |
| 14 | Rebind cùng URL | Không xử lý | Giữ request đang chạy, không huỷ rồi tải lại |
| 15 | `memoryCache.get` gọi 2 lần | Có thể `setImageBitmap(null)` | Lấy 1 lần vào biến |

### Lỗi phát sinh khi sửa (đã xử lý)

Bản sửa đầu, cuộn nhanh xong nhiều ô **xám mãi**. Nguyên nhân: ô tái dùng chờ `doOnLayout`, nhưng `setImageResource(placeholder)` làm ImageView "chờ layout" trong khi ConstraintLayout thấy cỡ không đổi nên bỏ qua layout nó → callback không bao giờ chạy. Sửa: View đã có cỡ thì bắt đầu luôn, chỉ View mới tạo mới chờ layout.

### Đánh đổi còn lại

Cuộn thật nhanh xuống rồi ngược lên, `dup` có thể tăng (lên 8 trong lần đo): ảnh của ô bị lướt qua đã bị huỷ giữa chừng nên phải tải lại. Muốn `dup` luôn = 0 thì khi ô cuối cùng huỷ vẫn cho tải nốt xuống đĩa (chỉ bỏ decode) — đổi lại tốn mạng cho ảnh đã lướt qua.

## 6. Test

- `UrlDownloaderTest` (14): validate đuôi/Content-Type, GET từ file có sẵn, `forceRefresh`, giới hạn dung lượng, dọn `.tmp`, huỷ không bắn callback.
- `BitmapDecoderTest` (6): tính `inSampleSize`.
- `MiniImageLoader` chưa có unit test (phụ thuộc `ImageView`) — đã kiểm bằng tay trên emulator theo các kịch bản ở mục 5.
