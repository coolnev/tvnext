# Audio-only AC3/EAC3 decoder

Unmodified Media3 1.11.0 FFmpeg Java/JNI glue (Apache-2.0) with an AC3/EAC3-only FFmpeg n6.0.1 build (LGPL-2.1-or-later). No video decoders or encoders are enabled. The upstream FFmpeg source archive, source URLs and checksums are included in this repository. Licenses are bundled in the APK assets.

The FFmpeg version follows the Media3 module's documented 6.0 build recommendation. JNI and codecs are built with the official NDK r28b, API24, for all four Android ABIs, using 16KiB ELF alignment. `SOURCES.json` records the exact source and binary checksums. No NDK download is needed to compile the app with the included libraries.

To rebuild:

```bash
python core/dolby/tools/build_native.py --ndk /path/to/android-ndk-r28b --work /tmp/tvnext-audio --host
```

The script builds the Android shared libraries and an optional Linux host library from the same sources. JDK headers are read through `JAVA_HOME`. The host build stubs only Android logging; the actual FFmpeg decoder and JNI processing are unchanged.

To run the real decoder tests:

```bash
TVNEXT_FFMPEG_HOST_LIBRARY=/tmp/tvnext-audio/host-jni gradle :core:dolby:testDebugUnitTest
```

The tests decode real generated AC3 and EAC3 5.1 packets, check the PCM channel layout, sample rate and non-zero centre/dialogue samples. Test vectors are synthetic sine audio, not programme recordings. The host test does not verify a physical Android AudioTrack or HDMI receiver. Application-level tests cover native SurfaceView selection, output-mode switching and actual stereo mixing of the centre channel.
