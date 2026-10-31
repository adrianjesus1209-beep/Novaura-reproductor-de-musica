/*
 * Copyright (c) 2025 Auxio Project
 * JStringRef.cpp is part of Auxio.
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
 
#include "JStringRef.h"
#include "util.h"

JStringRef::JStringRef(JNIEnv *env, jstring jString) : env(env), string(jString) {
}

JStringRef::JStringRef(JNIEnv *env, const TagLib::String string) : env(env) {
    TagLib::ByteVector utf16 = string.data(TagLib::String::UTF16LE);
    const jchar empty = 0;
    const jchar *chars =
            utf16.isEmpty() ?
                    &empty : reinterpret_cast<const jchar*>(utf16.data());
    this->string = env->NewString(chars, static_cast<jsize>(utf16.size() / 2));
}

JStringRef::~JStringRef() {
    if (string != nullptr) {
        env->DeleteLocalRef(string);
    }
}

TagLib::String JStringRef::copy() {
    if (string == nullptr) {
        return TagLib::String();
    }
    jsize length = env->GetStringLength(string);
    const jchar *chars = env->GetStringChars(string, nullptr);
    if (chars == nullptr) {
        return TagLib::String();
    }
    TagLib::String result(
            TagLib::ByteVector(reinterpret_cast<const char*>(chars),
                    static_cast<unsigned int>(length * 2)),
            TagLib::String::UTF16LE);
    env->ReleaseStringChars(string, chars);
    return result;
}

jstring& JStringRef::operator*() {
    return string;
}
