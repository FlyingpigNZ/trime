/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

static inline void throwJavaException(JNIEnv* env, const char* msg) {
  jclass c = env->FindClass("java/lang/Exception");
  env->ThrowNew(c, msg);
  env->DeleteLocalRef(c);
}

// --- UTF conversion helpers -------------------------------------------------
//
// JNI strings travel as *Modified* UTF-8 (surrogate pairs for supplementary
// characters), while librime speaks standard UTF-8 (4-byte sequences). Passing
// one where the other is expected corrupts emoji / CJK Ext-B text and
// non-ASCII paths, so every boundary conversion is explicit.

// Decode UTF-8 (standard or modified) into UTF-16 code units. 3-byte
// sequences that are surrogate halves (modified UTF-8) decode to the matching
// UTF-16 surrogate code unit, so a high+low pair becomes a proper UTF-16 pair
// for the same supplementary code point.
static inline std::vector<jchar> Utf8ToUtf16(const char* in, size_t len) {
  std::vector<jchar> out;
  out.reserve(len);
  size_t i = 0;
  while (i < len) {
    const auto c = static_cast<unsigned char>(in[i]);
    if (c < 0x80) {
      out.push_back(static_cast<jchar>(c));
      ++i;
    } else if ((c >> 5) == 0x6 && i + 1 < len) {
      out.push_back(static_cast<jchar>(
          ((c & 0x1Fu) << 6) | (static_cast<unsigned char>(in[i + 1]) & 0x3Fu)));
      i += 2;
    } else if ((c >> 4) == 0xE && i + 2 < len) {
      out.push_back(static_cast<jchar>(
          ((c & 0x0Fu) << 12) |
          ((static_cast<unsigned char>(in[i + 1]) & 0x3Fu) << 6) |
          (static_cast<unsigned char>(in[i + 2]) & 0x3Fu)));
      i += 3;
    } else if (i + 3 < len) {
      const auto cp = static_cast<uint32_t>(
          ((c & 0x07u) << 18) |
          ((static_cast<unsigned char>(in[i + 1]) & 0x3Fu) << 12) |
          ((static_cast<unsigned char>(in[i + 2]) & 0x3Fu) << 6) |
          (static_cast<unsigned char>(in[i + 3]) & 0x3Fu));
      if (cp >= 0x10000) {
        const auto v = cp - 0x10000;
        out.push_back(static_cast<jchar>(0xD800 + (v >> 10)));
        out.push_back(static_cast<jchar>(0xDC00 + (v & 0x3FF)));
      } else {
        out.push_back(static_cast<jchar>(cp));
      }
      i += 4;
    } else {
      // Malformed tail: keep the byte rather than dropping data.
      out.push_back(static_cast<jchar>(c));
      ++i;
    }
  }
  return out;
}

// Encode UTF-16 code units as standard UTF-8 (surrogate pairs combined).
static inline std::string Utf16ToUtf8(const std::vector<jchar>& in) {
  std::string out;
  out.reserve(in.size());
  for (size_t i = 0; i < in.size(); ++i) {
    const auto u = static_cast<uint32_t>(in[i]);
    if (u >= 0xD800 && u <= 0xDBFF && i + 1 < in.size()) {
      const auto lo = static_cast<uint32_t>(in[i + 1]);
      if (lo >= 0xDC00 && lo <= 0xDFFF) {
        const auto cp = 0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00);
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        ++i;
        continue;
      }
    }
    if (u < 0x80) {
      out.push_back(static_cast<char>(u));
    } else if (u < 0x800) {
      out.push_back(static_cast<char>(0xC0 | (u >> 6)));
      out.push_back(static_cast<char>(0x80 | (u & 0x3F)));
    } else {
      out.push_back(static_cast<char>(0xE0 | (u >> 12)));
      out.push_back(static_cast<char>(0x80 | ((u >> 6) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | (u & 0x3F)));
    }
  }
  return out;
}

// Convert a Java (modified UTF-8) byte string to standard UTF-8.
static inline std::string ModifiedUtf8ToUtf8(const char* in, jsize len) {
  return Utf16ToUtf8(Utf8ToUtf16(in, static_cast<size_t>(len)));
}

// Create a Java String from standard UTF-8. NewStringUTF must not be used with
// librime output: its 4-byte sequences are not valid Modified UTF-8.
static inline jstring NewUtf8String(JNIEnv* env, const char* in, size_t len) {
  const std::vector<jchar> utf16 = Utf8ToUtf16(in, len);
  return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

class CString {
 private:
  std::string owned_;  // standard UTF-8 representation for librime

 public:
  explicit CString(JNIEnv* env, jstring str) {
    if (str == nullptr) return;
    const jsize len = env->GetStringUTFLength(str);
    const char* modified = env->GetStringUTFChars(str, nullptr);
    if (modified != nullptr) {
      owned_ = ModifiedUtf8ToUtf8(modified, len);
      env->ReleaseStringUTFChars(str, modified);
    }
  }

  operator std::string() const { return owned_; }

  operator const char*() const { return owned_.c_str(); }

  const char* operator*() const { return owned_.c_str(); }
};

template <typename T = jobject>
class JRef {
 private:
  JNIEnv* env_;
  T ref_;

 public:
  JRef(JNIEnv* env, jobject ref) : env_(env), ref_(reinterpret_cast<T>(ref)) {}

  ~JRef() { env_->DeleteLocalRef(ref_); }

  operator T() { return ref_; }

  T operator*() { return ref_; }
};

class JString {
 private:
  JNIEnv* env_;
  jstring jstring_;

 public:
  JString(JNIEnv* env, const std::string& string)
      : env_(env),
        jstring_(NewUtf8String(env, string.data(), string.size())) {}

  JString(JNIEnv* env, const char* chars)
      : env_(env),
        jstring_(NewUtf8String(env, chars, chars ? std::strlen(chars) : 0)) {}

  ~JString() { env_->DeleteLocalRef(jstring_); }

  operator jstring() const { return jstring_; }

  jstring operator*() const { return jstring_; }
};

class JEnv {
 private:
  JNIEnv* env = nullptr;

 public:
  explicit JEnv(JavaVM* jvm) {
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) ==
        JNI_EDETACHED) {
      jvm->AttachCurrentThread(&env, nullptr);
    }
  }

  operator JNIEnv*() { return env; }

  JNIEnv* operator->() { return env; }
};

class GlobalRefSingleton {
 public:
  JavaVM* jvm;

  jclass Object;

  jclass String;

  jclass Integer;
  jmethodID IntegerInit;

  jclass Boolean;
  jmethodID BooleanInit;

  jclass Rime;
  jmethodID HandleRimeMessage;

  jclass CandidateProto;
  jmethodID CandidateProtoInit;

  jclass CommitProto;
  jmethodID CommitProtoInit;

  jclass ContextProto;
  jmethodID ContextProtoInit;

  jclass CompositionProto;
  jmethodID CompositionProtoInit;

  jclass StatusProto;
  jmethodID StatusProtoInit;

  jclass RimeResponse;
  jmethodID RimeResponseInit;

  jclass CandidatesPaged;
  jmethodID CandidatesPagedInit;

  jclass CandidatesBulk;
  jmethodID CandidatesBulkInit;

  jclass SchemaListItem;
  jmethodID SchemaListItemInit;

  jclass KeyEvent;
  jmethodID KeyEventInit;

  explicit GlobalRefSingleton(JavaVM* jvm_) : jvm(jvm_) {
    JNIEnv* env;
    jvm->AttachCurrentThread(&env, nullptr);

    Object = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("java/lang/Object")));

    String = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("java/lang/String")));

    Integer = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("java/lang/Integer")));
    IntegerInit = env->GetMethodID(Integer, "<init>", "(I)V");

    Boolean = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("java/lang/Boolean")));
    BooleanInit = env->GetMethodID(Boolean, "<init>", "(Z)V");

    Rime = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("com/osfans/trime/core/Rime")));
    HandleRimeMessage = env->GetStaticMethodID(Rime, "handleRimeMessage",
                                               "(I[Ljava/lang/Object;)V");

    CandidateProto = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("com/osfans/trime/core/CandidateProto")));
    CandidateProtoInit = env->GetMethodID(
        CandidateProto, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

    CommitProto = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("com/osfans/trime/core/CommitProto")));
    CommitProtoInit =
        env->GetMethodID(CommitProto, "<init>", "(Ljava/lang/String;)V");

    ContextProto = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("com/osfans/trime/core/ContextProto")));
    ContextProtoInit =
        env->GetMethodID(ContextProto, "<init>",
                         "(Lcom/osfans/trime/core/CompositionProto;Ljava/lang/"
                         "String;I)V");

    CompositionProto = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("com/osfans/trime/core/CompositionProto")));
    CompositionProtoInit =
        env->GetMethodID(CompositionProto, "<init>",
                         "(IIIILjava/lang/String;Ljava/lang/String;)V");

    StatusProto = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("com/osfans/trime/core/StatusProto")));
    StatusProtoInit =
        env->GetMethodID(StatusProto, "<init>",
                         "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V");

    RimeResponse = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("com/osfans/trime/core/RimeResponse")));
    RimeResponseInit = env->GetMethodID(
        RimeResponse, "<init>",
        "(Lcom/osfans/trime/core/CommitProto;Lcom/osfans/trime/core/"
        "CompositionProto;Lcom/osfans/trime/core/Candidates;"
        "Lcom/osfans/trime/core/StatusProto;)V");

    CandidatesPaged = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("com/osfans/trime/core/Candidates$Paged")));
    CandidatesPagedInit =
        env->GetMethodID(CandidatesPaged, "<init>",
                         "(ZZI[Lcom/osfans/trime/core/CandidateProto;)V");

    CandidatesBulk = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("com/osfans/trime/core/Candidates$Bulk")));
    CandidatesBulkInit =
        env->GetMethodID(CandidatesBulk, "<init>",
                         "(II[Lcom/osfans/trime/core/CandidateProto;)V");

    SchemaListItem = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("com/osfans/trime/core/SchemaItem")));
    SchemaListItemInit = env->GetMethodID(
        SchemaListItem, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");

    KeyEvent = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("com/osfans/trime/core/RimeKeyEvent")));
    KeyEventInit =
        env->GetMethodID(KeyEvent, "<init>", "(IILjava/lang/String;)V");
  }

  [[nodiscard]] JEnv AttachEnv() const { return JEnv(jvm); }
};

extern GlobalRefSingleton* GlobalRef;
