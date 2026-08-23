// SPDX-FileCopyrightText: 2015 - 2024 Rime community
// SPDX-License-Identifier: GPL-3.0-or-later

#include <rime/key_table.h>

#include "jni-utils.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_osfans_trime_core_RimeKeyEvent_getKeycodeByName(JNIEnv* env,
                                                         jclass /* thiz */,
                                                         jstring name) {
  return RimeGetKeycodeByName(CString(env, name));
}
