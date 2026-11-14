/*
 * Copyright (c) 2025 Auxio Project
 * JByteArrayRef.cpp is part of Auxio.
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
 
#include "JByteArrayRef.h"
#include "util.h"
#include <limits>

JByteArrayRef::JByteArrayRef(JNIEnv *env, TagLib::ByteVector &data) : env(env) {
    if (data.size()
            > static_cast<size_t>(std::numeric_limits < jsize > ::max())) {
        throw std::runtime_error("ByteVector exceeds maximum jsize");
    }
    auto size = static_cast<jsize>(data.size());
    array = env->NewByteArray(size);
    checkJniException(env, "NewByteArray threw JNI exception");
    if (array == nullptr) {
        throw std::runtime_error("array failed to allocate");
    }
    if (size > 0) {
        env->SetByteArrayRegion(array, 0, size,
                reinterpret_cast<const jbyte*>(data.data()));
        checkJniException(env, "SetByteArrayRegion threw JNI exception");
    }
}

JByteArrayRef::JByteArrayRef(JNIEnv *env, jbyteArray array) : env(env), array(
        array) {
}

JByteArrayRef::~JByteArrayRef() {
    if (array != nullptr) {
        env->DeleteLocalRef(array);
    }
}

TagLib::ByteVector JByteArrayRef::copy() {
    if (array == nullptr) {
        return TagLib::ByteVector();
    }
    jsize length = env->GetArrayLength(array);
    if (length <= 0) {
        return TagLib::ByteVector();
    }
    auto data = env->GetByteArrayElements(array, nullptr);
    if (data == nullptr) {
        checkJniException(env, "GetByteArrayElements threw JNI exception");
        return TagLib::ByteVector();
    }
    TagLib::ByteVector byteVector(reinterpret_cast<const char*>(data),
            static_cast<unsigned int>(length));
    env->ReleaseByteArrayElements(array, data, JNI_ABORT);
    return byteVector;
}

jbyteArray& JByteArrayRef::operator*() {
    return array;
}

