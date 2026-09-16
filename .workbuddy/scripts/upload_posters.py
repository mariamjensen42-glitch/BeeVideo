# -*- coding: utf-8 -*-
"""上传 7 张海报原图到 Ardot 临时资源位（register_assets 返回的 presigned PUT URL）。"""
import os
import urllib.request

IMG = r"D:\Programming\Kotlin\BeeVideo\docs\design\images"

PAIRS = [
    ("Cinematic_Chinese_thriller_mov_2026-09-15T07-17-36.png",
     "https://design-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/tmp/presigned/714732728211744/01M2HZ3N4W231C4TQE4RZWPM2E?sign=q-sign-algorithm%3Dsha1%26q-ak%3DAKIDVsHXijW5PUKygAPQC9RZT9ZkhPsDKCoA%26q-sign-time%3D1789456869%3B1789457829%26q-key-time%3D1789456869%3B1789457829%26q-header-list%3Dhost%26q-url-param-list%3D%26q-signature%3D1b68535aab35bc98055eda2efe592e310d507c19"),
    ("Cinematic_movie_poster_artwork_2026-09-15T07-17-56.png",
     "https://design-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/tmp/presigned/714732728211744/01M2HZ3N5A6X9EC3QYKH70W84F?sign=q-sign-algorithm%3Dsha1%26q-ak%3DAKIDVsHXijW5PUKygAPQC9RZT9ZkhPsDKCoA%26q-sign-time%3D1789456869%3B1789457829%26q-key-time%3D1789456869%3B1789457829%26q-header-list%3Dhost%26q-url-param-list%3D%26q-signature%3D8a0cd9854bd4a7ba98fffc97ca007fd9c5f35dc9"),
    ("Cinematic_mystery_movie_poster_2026-09-15T07-18-12.png",
     "https://design-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/tmp/presigned/714732728211744/01M2HZ3NNHEQJGYQ9ZEN4Y2XD5?sign=q-sign-algorithm%3Dsha1%26q-ak%3DAKIDVsHXijW5PUKygAPQC9RZT9ZkhPsDKCoA%26q-sign-time%3D1789456870%3B1789457830%26q-key-time%3D1789456870%3B1789457830%26q-header-list%3Dhost%26q-url-param-list%3D%26q-signature%3D108688fb6f8dec6f9be9e48dcf18e57cc8707fcb"),
    ("Cinematic_movie_poster_artwork_2026-09-15T07-18-30.png",
     "https://design-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/tmp/presigned/714732728211744/01M2HZ3NY0Z5117P5WPM6Q0429?sign=q-sign-algorithm%3Dsha1%26q-ak%3DAKIDVsHXijW5PUKygAPQC9RZT9ZkhPsDKCoA%26q-sign-time%3D1789456870%3B1789457830%26q-key-time%3D1789456870%3B1789457830%26q-header-list%3Dhost%26q-url-param-list%3D%26q-signature%3D433221d9f16691fd5aa19e97f7270929ac017483"),
    ("Cinematic_movie_poster_artwork_2026-09-15T07-18-47.png",
     "https://design-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/tmp/presigned/714732728211744/01M2HZ3NYA1MCX6FP0EPA1R3J7?sign=q-sign-algorithm%3Dsha1%26q-ak%3DAKIDVsHXijW5PUKygAPQC9RZT9ZkhPsDKCoA%26q-sign-time%3D1789456870%3B1789457830%26q-key-time%3D1789456870%3B1789457830%26q-header-list%3Dhost%26q-url-param-list%3D%26q-signature%3D51a749e3a9fe5bfb86f9298511093c0fbb450ebd"),
    ("Cinematic_movie_poster_artwork_2026-09-15T07-19-05.png",
     "https://design-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/tmp/presigned/714732728211744/01M2HZ3NQ0D2DGWBPR785VFJMQ?sign=q-sign-algorithm%3Dsha1%26q-ak%3DAKIDVsHXijW5PUKygAPQC9RZT9ZkhPsDKCoA%26q-sign-time%3D1789456870%3B1789457830%26q-key-time%3D1789456870%3B1789457830%26q-header-list%3Dhost%26q-url-param-list%3D%26q-signature%3Db1dde818ecc1b6a068702399b3b2341b3eede4f5"),
    ("Cinematic_movie_poster_artwork_2026-09-15T07-19-22.png",
     "https://design-tmp-1258344699.cos.ap-guangzhou.myqcloud.com/tmp/presigned/714732728211744/01M2HZ3NRDATGVF4AG9SQWHMY4?sign=q-sign-algorithm%3Dsha1%26q-ak%3DAKIDVsHXijW5PUKygAPQC9RZT9ZkhPsDKCoA%26q-sign-time%3D1789456870%3B1789457830%26q-key-time%3D1789456870%3B1789457830%26q-header-list%3Dhost%26q-url-param-list%3D%26q-signature%3D807c9183f44094fcbe700fcc6fef082a34f44826"),
]

ok = []
for name, url in PAIRS:
    path = os.path.join(IMG, name)
    with open(path, "rb") as f:
        data = f.read()
    req = urllib.request.Request(
        url, data=data, method="PUT",
        headers={"Content-Type": "image/png", "Content-Length": str(len(data))},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            print("OK  %s  %s  %d bytes" % (resp.status, name, len(data)))
            ok.append(name)
    except Exception as e:
        print("FAIL %s -> %r" % (name, e))

print("uploaded %d/%d" % (len(ok), len(PAIRS)))
