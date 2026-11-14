/*
 * Copyright (c) 2025 Auxio Project
 * JObjectRef.cpp is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
#include "JObjectRef.h"
#include "util.h"

JObjectRef::JObjectRef(JNIEnv *env, jobject object) : env(env), object(object) {
    checkJniException(env, "Pending JNI exception constructing JObjectRef");
    if (object == nullptr) {
        throw std::runtime_error("JObjectRef initialized with null jobject");
    }
}

JObjectRef::~JObjectRef() {
    if (object != nullptr) {
        env->DeleteLocalRef(object);
    }
}

jobject& JObjectRef::operator*() {
    return object;
}
