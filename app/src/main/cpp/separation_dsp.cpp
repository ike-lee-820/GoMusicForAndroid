// 固定周期 REPET-lite：无 AI、无训练数据的伴奏近似提取。
// 编译产物 libseparation_dsp.so 由 SepNative.reduce(...) 通过 JNI 调用。
#include <jni.h>
#include <vector>
#include <complex>
#include <cmath>
#include <algorithm>

static const int NFFT = 2048;
static const int HOP = 512;

typedef std::complex<float> C;

static void fft(std::vector<C>& a, bool inv) {
    int n = (int)a.size();
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
        float ang = (float)(2 * M_PI / len) * (inv ? 1.f : -1.f);
        C wl(std::cos(ang), std::sin(ang));
        for (int i = 0; i < n; i += len) {
            C w(1.f, 0.f);
            for (int k = 0; k < len / 2; k++) {
                C u = a[i + k], v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
    if (inv) for (int i = 0; i < n; i++) a[i] /= (float)n;
}

static void hann(std::vector<float>& w) {
    w.resize(NFFT);
    for (int i = 0; i < NFFT; i++)
        w[i] = 0.5f * (1.f - std::cos(2.f * (float)M_PI * i / (NFFT - 1)));
}

// 对单声道 PCM（float，-1..1）做 REPET-lite；strength 0..1
static void repet(const std::vector<float>& in, std::vector<float>& out, float strength) {
    int n = (int)in.size();
    out.assign((size_t)n + NFFT, 0.f);
    std::vector<float> win;
    hann(win);
    int frames = n / HOP + 1;
    int bins = NFFT / 2 + 1;
    std::vector<std::vector<C>> spec((size_t)frames, std::vector<C>((size_t)NFFT));
    for (int f = 0; f < frames; f++) {
        std::vector<C> a((size_t)NFFT, C(0.f, 0.f));
        int start = f * HOP;
        for (int i = 0; i < NFFT; i++) {
            int p = start + i;
            a[i] = C(p >= 0 && p < n ? in[p] * win[i] : 0.f, 0.f);
        }
        fft(a, false);
        spec[f].swap(a);
    }
    // 重复周期：约 0.75 秒（44.1kHz / hop512 ≈ 65 帧）
    int period = 64;
    std::vector<float> mags(4);
    for (int f = 0; f < frames; f++) {
        for (int b = 0; b < bins; b++) {
            float mag = std::abs(spec[f][b]);
            int cnt = 0;
            for (int k = -2; k <= 2; k++) {
                int g = f + k * period;
                if (g >= 0 && g < frames) mags[cnt++] = std::abs(spec[g][b]);
            }
            if (cnt > 0) {
                std::sort(mags.begin(), mags.begin() + cnt);
                float bg = mags[cnt / 2];
                float target = bg * strength;
                float keep = mag > 1e-6f ? (mag - target) / mag : 1.f;
                keep = std::max(0.f, std::min(1.f, keep));
                spec[f][b] *= keep;
            }
        }
    }
    std::vector<float> acc((size_t)n + NFFT, 0.f);
    for (int f = 0; f < frames; f++) {
        std::vector<C> a = spec[f];
        fft(a, true);
        int start = f * HOP;
        for (int i = 0; i < NFFT; i++) {
            int p = start + i;
            if (p >= 0 && p < n + NFFT) {
                out[p] += a[i].real() * win[i];
                acc[p] += win[i] * win[i];
            }
        }
    }
    for (int p = 0; p < n; p++) {
        float denom = acc[p] > 1e-6f ? acc[p] : 1.f;
        out[p] = out[p] / denom;
    }
    out.resize((size_t)n);
}

extern "C" JNIEXPORT void JNICALL
Java_de_myocd_gomusic_SepNative_reduce(JNIEnv* env, jobject, jshortArray arr,
                                       jint frames, jint channels, jfloat strength) {
    jsize len = env->GetArrayLength(arr);
    jshort* p = env->GetShortArrayElements(arr, nullptr);
    if (!p) return;
    int ch = channels > 0 ? channels : 1;
    if (ch >= 2) {
        // 中置抵消 + 低频回补（与 Kotlin 内核一致，便于 A/B）
        float lp = 0.f, a = 0.02f, bassKeep = 0.85f;
        float s = std::max(0.f, std::min(1.f, strength));
        for (int f = 0; f < frames; f++) {
            int i = f * ch;
            float l = p[i] / 32768.f, r = p[i + 1] / 32768.f;
            float mid = (l + r) * .5f, side = (l - r) * .5f;
            lp += a * (mid - lp);
            float bass = lp * bassKeep * s, keep = mid * (1.f - s);
            float nl = std::max(-1.f, std::min(1.f, side + keep + bass));
            float nr = std::max(-1.f, std::min(1.f, -side + keep + bass));
            p[i] = (jshort)(nl * 32767.f);
            p[i + 1] = (jshort)(nr * 32767.f);
        }
    } else {
        std::vector<float> in((size_t)len), out;
        for (int i = 0; i < len; i++) in[i] = p[i] / 32768.f;
        repet(in, out, strength);
        for (int i = 0; i < len && i < (int)out.size(); i++)
            p[i] = (jshort)(std::max(-1.f, std::min(1.f, out[i])) * 32767.f);
    }
    (void)frames;
    env->ReleaseShortArrayElements(arr, p, 0);
}
