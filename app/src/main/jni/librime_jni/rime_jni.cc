// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

#include <rime_api.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "frontend.h"
#include "jni-utils.h"
#include "objconv.h"
#include "session.h"

#define MAX_BUFFER_LENGTH 2048

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();
// librime is compiled as a static library, we have to link modules explicitly
static void declare_librime_module_dependencies() {
  rime_require_module_lua();
  rime_require_module_octagram();
  rime_require_module_predict();
}

class Rime {
 public:
  Rime() : rime(rime_get_api()) {}
  Rime(Rime const&) = delete;
  void operator=(Rime const&) = delete;

  static Rime& Instance() {
    static Rime instance;
    return instance;
  }

  // Returns true when a maintenance run was scheduled; false when no
  // maintenance will run at all (e.g. the workspace is already up to date and
  // `detect_modifications` found nothing). The Kotlin side gates READY on the
  // deploy message, so callers must synthesize one when this returns false.
  bool startup(bool fullCheck,
               const RimeNotificationHandler& notificationHandler) {
    if (!rime) return false;
    const char* userDir = getenv("RIME_USER_DATA_DIR");
    const char* sharedDir = getenv("RIME_SHARED_DATA_DIR");
    const char* versionName = getenv("RIME_DISTRIBUTION_VERSION");

    RIME_STRUCT(RimeTraits, trime_traits)
    trime_traits.shared_data_dir = sharedDir;
    trime_traits.user_data_dir = userDir;
    trime_traits.log_dir = "";  // set empty log_dir to log to logcat only
    trime_traits.app_name = "rime.trime";
    trime_traits.distribution_name = "Trime";
    trime_traits.distribution_code_name = "trime";
    trime_traits.distribution_version = versionName;

    rime->setup(&trime_traits);
    rime->initialize(&trime_traits);
    rime->set_notification_handler(notificationHandler, GlobalRef->jvm);
    return rime->start_maintenance(fullCheck);
  }

  // Whether the librime API was loaded at all; when false the engine cannot
  // ever become usable, so startup must report failure rather than success.
  bool apiAvailable() const { return rime != nullptr; }

  // RimeStartMaintenance returns false both for "no modifications" (steady
  // state — the engine is usable and a synthesized success is correct) and
  // for a failing installation_update (unwritable user dir, full disk — no
  // deploy message is ever sent, so a synthesized success would mask the
  // failure). Distinguish them by content, not mtime: InstallationUpdate only
  // rewrites installation.yaml when the recorded distribution/rime version
  // differs, so on a steady-state start the file exists and carries the
  // current distribution_version, while a failed installation_update leaves
  // the file missing or stale.
  bool installationInfoUsable() const {
    if (rime == nullptr) return false;
    const char* distroVersion = getenv("RIME_DISTRIBUTION_VERSION");
    if (distroVersion == nullptr) return false;
    // user_config_open reads the *user* data directory (user.yaml,
    // installation.yaml); config_open would look in the shared data
    // directory where installation.yaml never exists.
    RimeConfig cfg;
    if (!rime->user_config_open("installation", &cfg)) return false;
    const char* written =
        rime->config_get_cstring(&cfg, "distribution_version");
    const bool usable =
        written != nullptr && strcmp(written, distroVersion) == 0;
    rime->config_close(&cfg);
    return usable;
  }

  // Runs a full workspace deploy synchronously against the given directories.
  // Unlike startup(), this does not start the service or a maintenance thread,
  // so it is safe to use in a separate compile process without racing the live
  // engine (and in the same process only when no engine is running).
  bool deployWorkspace(const char* sharedDir, const char* userDir,
                       const char* versionName) {
    if (!rime) return false;
    RIME_STRUCT(RimeTraits, trime_traits)
    trime_traits.shared_data_dir = sharedDir;
    trime_traits.user_data_dir = userDir;
    trime_traits.log_dir = "";  // set empty log_dir to log to logcat only
    trime_traits.app_name = "rime.trime";
    trime_traits.distribution_name = "Trime";
    trime_traits.distribution_code_name = "trime";
    trime_traits.distribution_version = versionName;
    // setup() configures logging as well as the deployer paths;
    // deployer_initialize() then loads the deployer modules without starting
    // the service.
    rime->setup(&trime_traits);
    rime->deployer_initialize(&trime_traits);
    return rime->deploy();
  }

  bool processKey(int keycode, int mask) {
    return rime->process_key(session(), keycode, mask);
  }

  bool simulateKeySequence(const std::string& sequence) {
    return rime->simulate_key_sequence(session(), sequence.data());
  }

  bool commitComposition() { return rime->commit_composition(session()); }

  void clearComposition() { rime->clear_composition(session()); }

  std::unique_ptr<CommitProto> commit() {
    RIME_STRUCT(RimeCommit, data)
    if (rime->get_commit(session(), &data)) {
      auto p = std::make_unique<CommitProto>(&data);
      rime->free_commit(&data);
      return p;
    }
    return std::make_unique<CommitProto>();
  }

  std::unique_ptr<ContextProto> context(bool includeMenu = true) {
    RIME_STRUCT(RimeContext, data)
    auto s = session();
    if (rime->get_context(s, &data)) {
      auto input = rime->get_input(s);
      auto caretPos = rime->get_caret_pos(s);
      auto p =
          std::make_unique<ContextProto>(&data, input, caretPos, includeMenu);
      rime->free_context(&data);
      return p;
    }
    return std::make_unique<ContextProto>();
  }

  std::unique_ptr<StatusProto> status() {
    RIME_STRUCT(RimeStatus, data)
    if (rime->get_status(session(), &data)) {
      auto p = std::make_unique<StatusProto>(&data);
      rime->free_status(&data);
      return p;
    }
    return std::make_unique<StatusProto>();
  }

  void setOption(std::string_view key, bool value) {
    rime->set_option(session(), key.data(), value);
  }

  bool getOption(std::string_view key) {
    return rime->get_option(session(), key.data());
  }

  void setInput(std::string_view input) {
    rime->set_input(session(), input.data());
  }

  std::string currentSchemaId() {
    char result[MAX_BUFFER_LENGTH];
    return rime->get_current_schema(session(), result, MAX_BUFFER_LENGTH)
               ? result
               : "";
  }

  std::vector<SchemaItem> schemaList() {
    std::vector<SchemaItem> result;
    RimeSchemaList list{};
    if (rime->get_schema_list(&list)) {
      result = SchemaItem::fromCList(list);
      rime->free_schema_list(&list);
    }
    return std::move(result);
  }

  bool selectSchema(std::string_view schemaId) {
    return rime->select_schema(session(), schemaId.data());
  }

  std::string rawInput() {
    auto cStr = rime->get_input(session());
    return cStr ? cStr : "";
  }

  void setCaretPosition(size_t caretPos) {
    rime->set_caret_pos(session(), caretPos);
  }

  bool selectCandidate(size_t index, bool global) {
    if (global) {
      return rime->select_candidate(session(), index);
    } else {
      return rime->select_candidate_on_current_page(session(), index);
    }
  }

  bool deleteCandidate(size_t index, bool global) {
    if (global) {
      return rime->delete_candidate(session(), index);
    } else {
      return rime->delete_candidate_on_current_page(session(), index);
    }
  }

  bool changePage(bool backward) {
    return rime->change_page(session(), backward);
  }

  std::vector<CandidateProto> getCandidates(int startIndex, int limit) {
    std::vector<CandidateProto> result;
    result.reserve(limit);
    RimeCandidateListIterator iter{};
    if (rime->candidate_list_from_index(session(), &iter, startIndex)) {
      int count = 0;
      while (rime->candidate_list_next(&iter)) {
        if (count >= limit) break;
        result.emplace_back(iter.candidate);
        ++count;
      }
      rime->candidate_list_end(&iter);
    }
    return std::move(result);
  }

  std::tuple<int, int, std::vector<CandidateProto>> getBulkCandidates() {
    constexpr int limit = 16;
    auto list = getCandidates(0, limit);
    // use -1 to indicate it's not sure how many candidates now
    auto size = list.size() < limit ? list.size() : -1;
    auto highlighted = rime_get_highlighted_candidate_index(session());
    return std::make_tuple(size, highlighted, std::move(list));
  }

  void exit() {
    session_.reset();
    rime->finalize();
  }

  bool sync() {
    session_.reset();
    return rime->sync_user_data();
  }

 private:
  RimeApi* rime;
  std::shared_ptr<SessionHolder> session_;

  RimeSessionId session(bool requestNewSession = true) {
    if (!session_ && requestNewSession) {
      try {
        auto newSession = std::make_shared<SessionHolder>();
        session_ = newSession;
      } catch (...) {
        session_ = nullptr;
      }
    }
    if (!session_) {
      return 0;
    }
    return session_->id();
  }
};

GlobalRefSingleton* GlobalRef;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void* reserved) {
  GlobalRef = new GlobalRefSingleton(jvm);
  declare_librime_module_dependencies();
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL Java_com_osfans_trime_core_Rime_startupRime(
    JNIEnv* env, jclass clazz, jstring shared_dir, jstring user_dir,
    jstring version_name, jboolean full_check) {
  // for rime shared data dir
  setenv("RIME_SHARED_DATA_DIR", CString(env, shared_dir), 1);
  // for rime user data dir
  setenv("RIME_USER_DATA_DIR", CString(env, user_dir), 1);
  setenv("RIME_DISTRIBUTION_VERSION", CString(env, version_name), 1);

  auto notificationHandler = [](void* context_object, RimeSessionId session_id,
                                const char* message_type,
                                const char* message_value) {
    auto env = GlobalRef->AttachEnv();
    int type = 0;  // unknown
    if (strcmp(message_type, "schema") == 0) {
      type = 1;
    } else if (strcmp(message_type, "option") == 0) {
      type = 2;
    } else if (strcmp(message_type, "deploy") == 0) {
      type = 3;
    }
    auto vararg = JRef<jobjectArray>(
        env, env->NewObjectArray(1, GlobalRef->Object, nullptr));
    env->SetObjectArrayElement(vararg, 0, JString(env, message_value));
    env->CallStaticVoidMethod(GlobalRef->Rime, GlobalRef->HandleRimeMessage,
                              type, *vararg);
  };

  if (!Rime::Instance().startup(full_check, notificationHandler)) {
    // No maintenance was scheduled: either the workspace is already up to
    // date (steady state) or a maintenance thread is already running. In both
    // cases librime will not send a deploy message, yet the engine is usable
    // and the Kotlin READY gate must be unblocked — synthesize a success
    // notification through the same channel the maintenance thread uses.
    //
    // Do NOT synthesize success when the engine cannot possibly be usable: an
    // unloaded librime API, or a failing installation_update (unwritable user
    // dir, full disk) on a fresh workspace, where no installation.yaml was
    // ever written and no ("deploy","failure") message will ever arrive. Both
    // must land in FAILED instead of silently flipping to READY.
    if (Rime::Instance().apiAvailable() &&
        Rime::Instance().installationInfoUsable()) {
      notificationHandler(nullptr, 0, "deploy", "success");
    } else {
      notificationHandler(nullptr, 0, "deploy", "failure");
    }
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_Rime_exitRime(JNIEnv* env, jclass /* thiz */) {
  Rime::Instance().exit();
}

// deployment
extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_deployRimeWorkspace(JNIEnv* env,
                                                    jclass /* thiz */,
                                                    jstring shared_dir,
                                                    jstring user_dir,
                                                    jstring version_name) {
  return Rime::Instance().deployWorkspace(*CString(env, shared_dir),
                                          *CString(env, user_dir),
                                          *CString(env, version_name));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_syncRimeUserData(JNIEnv* env,
                                                 jclass /* thiz */) {
  return Rime::Instance().sync();
}

// input
extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_processRimeKey(JNIEnv* env, jclass /* thiz */,
                                               jint keycode, jint mask) {
  return Rime::Instance().processKey(keycode, mask);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_commitRimeComposition(JNIEnv* env,
                                                      jclass /* thiz */) {
  return Rime::Instance().commitComposition();
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_Rime_clearRimeComposition(JNIEnv* env,
                                                     jclass /* thiz */) {
  Rime::Instance().clearComposition();
}

// output
extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_Rime_getRimeCommit(JNIEnv* env, jclass /* thiz */) {
  auto commit = Rime::Instance().commit();
  return rimeCommitToJObject(env, *commit);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_Rime_getRimeContext(JNIEnv* env, jclass /* thiz */) {
  auto context = Rime::Instance().context();
  return rimeContextToJObject(env, *context);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_Rime_getRimeStatus(JNIEnv* env, jclass /* thiz */) {
  auto status = Rime::Instance().status();
  return rimeStatusToJObject(env, *status);
}

// runtime options
extern "C" JNIEXPORT void JNICALL Java_com_osfans_trime_core_Rime_setRimeOption(
    JNIEnv* env, jclass /* thiz */, jstring option, jboolean value) {
  Rime::Instance().setOption(*CString(env, option), value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_getRimeOption(JNIEnv* env, jclass /* thiz */,
                                              jstring option) {
  return Rime::Instance().getOption(*CString(env, option));
}

extern "C" JNIEXPORT void JNICALL Java_com_osfans_trime_core_Rime_setRimeInput(
    JNIEnv* env, jclass /* thiz */, jstring input) {
  Rime::Instance().setInput(*CString(env, input));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_osfans_trime_core_Rime_getRimeSchemaList(JNIEnv* env,
                                                  jclass /* thiz */) {
  return rimeSchemaListToJObjectArray(env, Rime::Instance().schemaList());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_core_Rime_getCurrentRimeSchema(JNIEnv* env,
                                                     jclass /* thiz */) {
  const std::string id = Rime::Instance().currentSchemaId();
  return NewUtf8String(env, id.data(), id.size());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_selectRimeSchema(JNIEnv* env, jclass /* thiz */,
                                                 jstring schema_id) {
  return Rime::Instance().selectSchema(*CString(env, schema_id));
}

// testing
extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_simulateRimeKeySequence(JNIEnv* env,
                                                        jclass /* thiz */,
                                                        jstring key_sequence) {
  return Rime::Instance().simulateKeySequence(CString(env, key_sequence));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_core_Rime_getRimeRawInput(JNIEnv* env,
                                                jclass /* thiz */) {
  const std::string input = Rime::Instance().rawInput();
  return NewUtf8String(env, input.data(), input.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_core_Rime_setRimeCaretPos(JNIEnv* env, jclass /* thiz */,
                                                jint caret_pos) {
  Rime::Instance().setCaretPosition(caret_pos);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_selectRimeCandidate(JNIEnv* env,
                                                    jclass /* thiz */,
                                                    jint index,
                                                    jboolean global) {
  return Rime::Instance().selectCandidate(index, global);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_deleteRimeCandidate(JNIEnv* env,
                                                    jclass /* thiz */,
                                                    jint index,
                                                    jboolean global) {
  return Rime::Instance().deleteCandidate(index, global);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_core_Rime_changeRimeCandidatePage(JNIEnv* env,
                                                        jclass clazz,
                                                        jboolean backward) {
  return Rime::Instance().changePage(backward);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_osfans_trime_core_Rime_getRimeCandidates(JNIEnv* env, jclass clazz,
                                                  jint start_index,
                                                  jint limit) {
  return rimeCandidateListToJObjectArray(
      env, Rime::Instance().getCandidates(start_index, limit));
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_osfans_trime_core_Rime_getRimeResponse(JNIEnv* env, jclass clazz,
                                                jboolean paging_mode) {
  auto commit = Rime::Instance().commit();
  // the menu is only needed in paging mode, otherwise its candidates would be
  // duplicated by the bulk candidates query below
  auto context = Rime::Instance().context(paging_mode);
  auto status = Rime::Instance().status();
  auto jCommit = JRef(env, rimeCommitToJObject(env, *commit));
  auto jComposition =
      JRef(env, rimeCompositionToJObject(env, context->composition));
  auto jStatus = JRef(env, rimeStatusToJObject(env, *status));
  // keep the local references alive until RimeResponse is constructed below
  jobject jCandidates = nullptr;
  if (paging_mode) {
    jCandidates = rimeCandidatesPagedToJObject(env, context->menu);
  } else {
    auto [size, highlighted, list] = Rime::Instance().getBulkCandidates();
    auto jList =
        JRef<jobjectArray>(env, rimeCandidateListToJObjectArray(env, list));
    jCandidates =
        env->NewObject(GlobalRef->CandidatesBulk, GlobalRef->CandidatesBulkInit,
                       size, highlighted, *jList);
  }
  return rimeResponseToJObject(env, jCommit, jComposition, jCandidates,
                               jStatus);
}
