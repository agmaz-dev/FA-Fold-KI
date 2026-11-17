// FA Fold KI: lokales Sprachmodell (GGUF) auf dem Handy über llama.cpp (JNI).
// Übernommen aus agmaz-dev/Jarvis-Android (app/src/main/cpp/jarvis_llm.cpp) und angepasst:
// eigene JNI-Namen (de.foldki.app.ai.LlamaNative), Kontext bis 8192, Ausgabe bis 4000 Tokens,
// Sampler-Werte pro Aufruf, "Denken" an/aus für Thinking-Modelle (Qwen3 & Co.).
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"
#include "chat.h"
#include "common.h"

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static common_chat_templates_ptr g_tmpl;
static std::vector<llama_token> g_cache;   // was gerade im KV-Speicher steht (für schnelle Folgefragen)
static std::atomic<bool> g_stop{false};
static std::atomic<bool> g_think_open{false};   // Vorlage endet mit <think>: Ausgabe beginnt mit Gedanken
static std::mutex g_mu;
static std::string g_err;

static void free_all() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_tmpl.reset();
    g_cache.clear();
}

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// Länge des gültigen UTF-8-Anfangs (unvollständige Zeichen am Ende zurückhalten)
static size_t utf8_valid_prefix(const std::string &s) {
    size_t n = s.size(), i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }
        int need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return (back + 1 >= need) ? n : i - 1;
    }
    return n;
}

static bool ends_with_think(const std::string &s) {
    size_t e = s.find_last_not_of(" \n\r\t");
    if (e == std::string::npos) return false;
    std::string t = s.substr(0, e + 1);
    const std::string tag = "<think>";
    return t.size() >= tag.size() && t.compare(t.size() - tag.size(), tag.size(), tag) == 0;
}

// Welche Rechen-Beschleuniger aktiv sind (nur die wichtigen, als kurzer Text)
static std::string cpu_flags() {
    std::string all = llama_print_system_info(), out;
    for (const char *k : {"DOTPROD = 1", "MATMUL_INT8 = 1", "KLEIDIAI = 1", "REPACK = 1", "SVE = 1"}) {
        if (all.find(k) != std::string::npos) { if (!out.empty()) out += ","; out += std::string(k).substr(0, std::string(k).find(' ')); }
    }
    return out.empty() ? "basic" : out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nCtx, jint nThreads) {
    std::lock_guard<std::mutex> lk(g_mu);
    free_all();
    g_err.clear();
    static bool backend = false;
    if (!backend) { llama_backend_init(); backend = true; }
    std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { g_err = "Modell konnte nicht geladen werden (Datei defekt, kein GGUF oder zu wenig Arbeitsspeicher)."; return JNI_FALSE; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx > 0 ? nCtx : 8192;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    // Antwort schreiben: wenige Threads (Speicher ist der Engpass), Eingabe lesen: mehr Threads
    cp.n_threads = nThreads > 0 ? nThreads : 4;
    cp.n_threads_batch = std::min(8, cp.n_threads + 2);
    cp.swa_full = true;   // damit der Gesprächsanfang wiederverwendet werden kann
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.type_k = GGML_TYPE_Q8_0;
    cp.type_v = GGML_TYPE_Q8_0;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        // Ersatz 1: ohne komprimierten KV-Speicher, halber Kontext
        cp.type_k = GGML_TYPE_F16; cp.type_v = GGML_TYPE_F16; cp.n_ctx = std::min<uint32_t>(cp.n_ctx, 4096);
        g_ctx = llama_init_from_model(g_model, cp);
    }
    if (!g_ctx) {
        // Ersatz 2: kleinster sinnvoller Kontext
        cp.n_ctx = 2048;
        g_ctx = llama_init_from_model(g_model, cp);
    }
    if (!g_ctx) { g_err = "Zu wenig Arbeitsspeicher für dieses Modell."; free_all(); return JNI_FALSE; }
    try {
        g_tmpl = common_chat_templates_init(g_model, "");
    } catch (...) {
        g_tmpl.reset();
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_err.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeContextSize(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_ctx ? (jint) llama_n_ctx(g_ctx) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeThinkOpen(JNIEnv *, jclass) { return g_think_open ? JNI_TRUE : JNI_FALSE; }

extern "C" JNIEXPORT void JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeStop(JNIEnv *, jclass) { g_stop = true; }

extern "C" JNIEXPORT void JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeUnload(JNIEnv *, jclass) {
    g_stop = true;
    std::lock_guard<std::mutex> lk(g_mu);
    free_all();
}

// roles/contents: parallele Arrays; cb.onToken(byte[]) wird für jedes Textstück aufgerufen.
// think = false: bei Thinking-Vorlagen wird ein leerer Denkblock vorgegeben (schnelle interne Aufgaben).
// thinkBudget > 0: nach so vielen Tokens im Denkblock wird "</think>" eingefügt, damit die Antwort sicher kommt.
extern "C" JNIEXPORT jstring JNICALL
Java_de_foldki_app_ai_LlamaNative_nativeGenerate(JNIEnv *env, jclass, jobjectArray roles, jobjectArray contents,
                                                 jint maxTokens, jfloat temp, jint topK, jfloat topP, jboolean think, jint thinkBudget, jobject cb) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_stop = false;
    g_think_open = false;
    if (!g_model || !g_ctx) return env->NewStringUTF("{\"error\":\"Kein Modell geladen.\"}");
    jclass cbc = env->GetObjectClass(cb);
    jmethodID onTok = env->GetMethodID(cbc, "onToken", "([B)V");
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // 1) Chat-Vorlage des Modells (Jinja aus der GGUF-Datei) anwenden
    std::vector<common_chat_msg> msgs;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        jstring jr = (jstring) env->GetObjectArrayElement(roles, i);
        jstring jc = (jstring) env->GetObjectArrayElement(contents, i);
        m.role = jstr(env, jr);
        m.content = jstr(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs.push_back(m);
    }
    std::vector<llama_token> toks;
    std::string rendered;
    bool templ_ok = false;
    if (g_tmpl) {
        try {
            common_chat_templates_inputs in;
            in.messages = msgs;
            in.add_generation_prompt = true;
            in.use_jinja = true;
            in.enable_thinking = think;
            common_chat_params p = common_chat_templates_apply(g_tmpl.get(), in);
            bool first = true;
            for (const auto &part : p.prompt.parts) {
                auto t = common_tokenize(vocab, part.text, first && llama_vocab_get_add_bos(vocab), !part.is_input);
                toks.insert(toks.end(), t.begin(), t.end());
                rendered += part.text;
                first = false;
            }
            templ_ok = true;
        } catch (...) { toks.clear(); rendered.clear(); }
    }
    if (!templ_ok) {
        // Notlösung ohne Vorlage: ChatML (Qwen-Format)
        std::string s;
        for (auto &m : msgs) s += "<|im_start|>" + m.role + "\n" + m.content + "<|im_end|>\n";
        s += "<|im_start|>assistant\n";
        rendered = s;
        toks = common_tokenize(vocab, s, true, true);
    }
    if (ends_with_think(rendered)) {
        if (think) g_think_open = true;
        else {
            // Thinking-Modell, aber jetzt ohne Nachdenken: leeren Denkblock vorgeben
            auto t = common_tokenize(vocab, "\n</think>\n\n", false, true);
            toks.insert(toks.end(), t.begin(), t.end());
        }
    }
    int n_ctx = (int) llama_n_ctx(g_ctx);
    int limit = maxTokens > 0 ? maxTokens : 700;
    // Passt die Antwort nicht mehr ganz in den Kontext: Antwortlänge kürzen, mindestens 256 Tokens
    if ((int) toks.size() + limit > n_ctx - 8) limit = n_ctx - 8 - (int) toks.size();
    if (limit < 256) return env->NewStringUTF("{\"error\":\"Die Unterhaltung ist zu lang für das Modell. Bitte einen neuen Chat beginnen.\"}");

    const int64_t t0 = ggml_time_us();
    // 2) Gemeinsamen Anfang mit dem letzten Mal wiederverwenden
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t keep = 0;
    while (keep < g_cache.size() && keep < toks.size() && g_cache[keep] == toks[keep]) keep++;
    if (keep == toks.size() && keep > 0) keep--;
    if (keep < g_cache.size()) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
            llama_memory_clear(mem, true);
            keep = 0;
        }
    }
    g_cache.resize(keep);

    // 3) Neue Eingabe verarbeiten
    int nb = (int) llama_n_batch(g_ctx);
    for (size_t i = keep; i < toks.size(); i += nb) {
        if (g_stop) return env->NewStringUTF("{\"stopped\":true}");
        int cnt = (int) std::min((size_t) nb, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, cnt);
        if (llama_decode(g_ctx, b) != 0) {
            llama_memory_clear(mem, true);
            g_cache.clear();
            return env->NewStringUTF("{\"error\":\"Fehler beim Verarbeiten der Eingabe (zu wenig Speicher?).\"}");
        }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + cnt);
    }

    const int64_t t1 = ggml_time_us();
    const size_t reused = keep;
    // 4) Antwort erzeugen
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp > 0 ? temp : 0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string pending;
    int generated = 0;
    bool cut = false;
    // Denk-Budget: zählt Tokens, solange der Denkblock offen ist (nur wenn die Ausgabe mit Gedanken beginnt)
    bool think_done = !g_think_open.load();
    bool think_cut = false;
    int think_tokens = 0;
    std::string think_tail;
    for (int i = 0; !g_stop; i++) {
        if (i >= limit) { cut = true; break; }
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char buf[256];
        int len = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
        if (len > 0) pending.append(buf, len);
        size_t ok = utf8_valid_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray((jsize) ok);
            env->SetByteArrayRegion(arr, 0, (jsize) ok, (const jbyte *) pending.data());
            env->CallVoidMethod(cb, onTok, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
        }
        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(g_ctx, b) != 0) break;
        generated++;
        g_cache.push_back(t);
        if (!think_done) {
            if (len > 0) think_tail.append(buf, len);
            if (think_tail.find("</think>") != std::string::npos) {
                think_done = true;
            } else {
                if (think_tail.size() > 32) think_tail.erase(0, think_tail.size() - 32);
                think_tokens++;
                if (thinkBudget > 0 && think_tokens >= thinkBudget) {
                    // Zu langes Nachdenken: Denkblock selbst schließen, das Modell schreibt danach die Antwort
                    static const std::string close_txt = "\n</think>\n\n";
                    auto ct = common_tokenize(vocab, close_txt, false, true);
                    if (!ct.empty() && (int) g_cache.size() + (int) ct.size() < n_ctx - 8) {
                        // unvollständige UTF-8-Reste vor dem Einfügen verwerfen
                        pending.clear();
                        jbyteArray arr = env->NewByteArray((jsize) close_txt.size());
                        env->SetByteArrayRegion(arr, 0, (jsize) close_txt.size(), (const jbyte *) close_txt.data());
                        env->CallVoidMethod(cb, onTok, arr);
                        env->DeleteLocalRef(arr);
                        llama_batch cb2 = llama_batch_get_one(ct.data(), (int32_t) ct.size());
                        if (llama_decode(g_ctx, cb2) != 0) break;
                        g_cache.insert(g_cache.end(), ct.begin(), ct.end());
                        think_cut = true;
                    }
                    think_done = true;
                }
            }
        }
        if ((int) g_cache.size() >= n_ctx - 2) { cut = true; break; }
    }
    llama_sampler_free(smpl);
    const int64_t t2 = ggml_time_us();
    char stats[700];
    snprintf(stats, sizeof(stats),
             "{\"prompt\":%d,\"reused\":%d,\"t_prompt\":%lld,\"gen\":%d,\"t_gen\":%lld,\"limit\":%d,\"cut\":%s,\"ctx\":%d,\"tmpl\":%s,\"think_open\":%s,\"think_cut\":%s,\"threads\":%d,\"cpu\":\"%s\"}",
             (int) toks.size(), (int) reused, (long long) ((t1 - t0) / 1000), generated, (long long) ((t2 - t1) / 1000),
             limit, cut ? "true" : "false", n_ctx, templ_ok ? "true" : "false", g_think_open ? "true" : "false", think_cut ? "true" : "false",
             (int) llama_n_threads(g_ctx), cpu_flags().c_str());
    return env->NewStringUTF(stats);
}
