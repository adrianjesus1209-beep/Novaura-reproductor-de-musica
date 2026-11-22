/*
 * Copyright (c) 2024 Auxio Project
 * JMetadataBuilder.cpp is part of Auxio.
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
 
#include "JMetadataBuilder.h"

#include "util.h"

#include <taglib/mp4tag.h>
#include <taglib/textidentificationframe.h>
#include <taglib/attachedpictureframe.h>

#include <taglib/tpropertymap.h>

#include "JObjectRef.h"
#include "JClassRef.h"
#include "JStringRef.h"
#include "JByteArrayRef.h"

JMetadataBuilder::JMetadataBuilder(JNIEnv *env) : env(env), id3v2(env), xiph(
        env), mp4(env), cover(), properties(nullptr) {
}

void JMetadataBuilder::setMimeType(TagLib::String type) {
    mimeType = type;
}

void JMetadataBuilder::setFallbackTag(TagLib::Tag &tag) {
    if (!tag.title().isEmpty() && populatedId3Keys.insert("TIT2").second) {
        id3v2.add_id("TIT2", tag.title());
    }
    if (!tag.artist().isEmpty() && populatedId3Keys.insert("TPE1").second) {
        id3v2.add_id("TPE1", tag.artist());
    }
    if (!tag.album().isEmpty() && populatedId3Keys.insert("TALB").second) {
        id3v2.add_id("TALB", tag.album());
    }
    if (tag.track() > 0 && populatedId3Keys.insert("TRCK").second) {
        id3v2.add_id("TRCK", std::to_string(tag.track()));
    }
    if (tag.year() > 0 && populatedId3Keys.insert("TYER").second) {
        id3v2.add_id("TYER", std::to_string(tag.year()));
    }
    if (!tag.genre().isEmpty() && populatedId3Keys.insert("TCON").second) {
        id3v2.add_id("TCON", tag.genre());
    }
}

void JMetadataBuilder::setId3v1(TagLib::ID3v1::Tag &tag) {
    if (!tag.title().isEmpty() && populatedId3Keys.insert("TIT2").second) {
        id3v2.add_id("TIT2", tag.title());
    }
    if (!tag.artist().isEmpty() && populatedId3Keys.insert("TPE1").second) {
        id3v2.add_id("TPE1", tag.artist());
    }
    if (!tag.album().isEmpty() && populatedId3Keys.insert("TALB").second) {
        id3v2.add_id("TALB", tag.album());
    }
    if (tag.track() > 0 && populatedId3Keys.insert("TRCK").second) {
        id3v2.add_id("TRCK", std::to_string(tag.track()));
    }
    if (tag.year() > 0 && populatedId3Keys.insert("TYER").second) {
        id3v2.add_id("TYER", std::to_string(tag.year()));
    }
    const int genreNumber = tag.genreNumber();
    if (genreNumber != 255 && populatedId3Keys.insert("TCON").second) {
        id3v2.add_id("TCON", std::to_string(genreNumber));
    }
}

void JMetadataBuilder::setId3v2(TagLib::ID3v2::Tag &tag) {
    // We want to ideally find the front cover, fall back to the first picture otherwise.
    std::optional<TagLib::ID3v2::AttachedPictureFrame*> firstPic;
    std::optional<TagLib::ID3v2::AttachedPictureFrame*> frontCoverPic;
    for (auto frame : tag.frameList()) {
        if (auto txxxFrame =
                dynamic_cast<TagLib::ID3v2::UserTextIdentificationFrame*>(frame)) {
            TagLib::String id = frame->frameID();
            TagLib::StringList frameText = txxxFrame->fieldList();
            if (frameText.isEmpty())
                continue;
            auto begin = frameText.begin();
            TagLib::String description = *begin;
            frameText.erase(begin);
            id3v2.add_combined(id, description, frameText);
        } else if (auto textFrame =
                dynamic_cast<TagLib::ID3v2::TextIdentificationFrame*>(frame)) {
            TagLib::String key = frame->frameID();
            TagLib::StringList frameText = textFrame->fieldList();
            if (!frameText.isEmpty()) {
                populatedId3Keys.insert(key.to8Bit(true));
                id3v2.add_id(key, frameText);
            }
        } else if (auto pictureFrame =
                dynamic_cast<TagLib::ID3v2::AttachedPictureFrame*>(frame)) {
            if (pictureFrame->picture().isEmpty()) {
                continue;
            }
            if (!firstPic) {
                firstPic = pictureFrame;
            }
            if (!frontCoverPic
                    && pictureFrame->type()
                            == TagLib::ID3v2::AttachedPictureFrame::FrontCover) {
                frontCoverPic = pictureFrame;
            }
        } else {
            continue;
        }
    }
    if (frontCoverPic) {
        auto pic = *frontCoverPic;
        if (!pic->picture().isEmpty()) {
            cover = pic->picture();
            hasFrontCover = true;
        }
    } else if (firstPic && !cover.has_value()) {
        auto pic = *firstPic;
        if (!pic->picture().isEmpty()) {
            cover = pic->picture();
        }
    }
}

void JMetadataBuilder::setXiph(TagLib::Ogg::XiphComment &tag) {
    for (auto field : tag.fieldListMap()) {
        auto key = field.first.upper();
        auto values = field.second;
        xiph.add_custom(key, values);
    }
    auto pics = tag.pictureList();
    setFlacPictures(pics);
}

template<typename T>
void mp4AddImpl(JTagMap &map, TagLib::String &itemName, T itemValue) {
    if (itemName.startsWith("----")) {
        // Split this into it's atom name and description
        int splitPos = itemName.find(':');
        if (splitPos != -1) {
            auto atomName = itemName.substr(0, splitPos);
            auto atomDescription = itemName.substr(splitPos + 1);
            map.add_combined(atomName, atomDescription, itemValue);
        } else {
            map.add_id(itemName, itemValue);
        }
    } else {
        map.add_id(itemName, itemValue);
    }
}

void JMetadataBuilder::setMp4(TagLib::MP4::Tag &tag) {
    auto map = tag.itemMap();
    for (auto item : map) {
        auto itemName = item.first;
        auto itemValue = item.second;
        if (itemName == "covr") {
            // Special cover case.
            // MP4 has no types, so just prioritize easier to decode covers (PNG, JPEG)
            auto pics = itemValue.toCoverArtList();
            for (auto &pic : pics) {
                if (pic.data().isEmpty()) {
                    continue;
                }
                auto format = pic.format();
                if (format == TagLib::MP4::CoverArt::PNG
                        || format == TagLib::MP4::CoverArt::JPEG) {
                    cover = pic.data();
                    break;
                }
            }
            if (!cover.has_value() || cover->isEmpty()) {
                for (auto &pic : pics) {
                    if (!pic.data().isEmpty()) {
                        cover = pic.data();
                        break;
                    }
                }
            }
            continue;
        }
        auto type = itemValue.type();
        std::string serializedValue;
        switch (type) {
        // Normal expected MP4 items
        case TagLib::MP4::Item::Type::StringList:
            mp4AddImpl(mp4, itemName, itemValue.toStringList());
            continue;
            // Weird MP4 items I'm 90% sure I'll encounter.
        case TagLib::MP4::Item::Type::Bool:
            serializedValue = itemValue.toBool() ? "1" : "0";
            break;
        case TagLib::MP4::Item::Type::Int:
            serializedValue = std::to_string(itemValue.toInt());
            break;
        case TagLib::MP4::Item::Type::UInt:
            serializedValue = std::to_string(itemValue.toUInt());
            break;
        case TagLib::MP4::Item::Type::LongLong:
            serializedValue = std::to_string(itemValue.toLongLong());
            break;
        case TagLib::MP4::Item::Type::IntPair:
            // It's inefficient going from the integer representation back into
            // a string, but I fully expect taggers to just write "NN/TT" strings
            // anyway, and musikr doesn't have to do as much fiddly variant handling.
            serializedValue = std::to_string(itemValue.toIntPair().first) + "/"
                    + std::to_string(itemValue.toIntPair().second);
            break;
        default:
            // Don't care about the other types
            continue;
        }
        mp4AddImpl(mp4, itemName, TagLib::String(serializedValue));
    }
}

void JMetadataBuilder::setMatroska(TagLib::Matroska::File &file) {
    auto props = file.properties();
    for (const auto &entry : props) {
        xiph.add_custom(entry.first.upper(), entry.second);
    }
    auto pics = file.complexProperties("PICTURE");
    for (const auto &pic : pics) {
        auto it = pic.find("data");
        if (it != pic.end()) {
            bool ok = false;
            TagLib::ByteVector data = it->second.toByteVector(&ok);
            if (ok && !data.isEmpty()) {
                cover = data;
                break;
            }
        }
    }
}

void JMetadataBuilder::setFlacPictures(
        TagLib::List<TagLib::FLAC::Picture*> &pics) {
    // Find the front cover image. If it doesn't exist, fall back to the first image
    // only when no cover has already been selected.
    for (auto pic : pics) {
        if (pic->type() == TagLib::FLAC::Picture::FrontCover
                && !pic->data().isEmpty()) {
            cover = pic->data();
            hasFrontCover = true;
            return;
        }
    }
    if (!hasFrontCover && (!cover.has_value() || cover->isEmpty())) {
        for (auto pic : pics) {
            if (!pic->data().isEmpty()) {
                cover = pic->data();
                return;
            }
        }
    }
}

void JMetadataBuilder::setProperties(TagLib::AudioProperties *properties) {
    this->properties = properties;
}

jobject JMetadataBuilder::build() {
    if (properties == nullptr) {
        throw std::runtime_error(
                "AudioProperties is null in JMetadataBuilder::build");
    }
    JClassRef jPropertiesClass { env, "org/oxycblt/musikr/metadata/Properties" };
    jmethodID jPropertiesInitMethod = jPropertiesClass.method("<init>",
            "(Ljava/lang/String;JII)V");
    JStringRef jMimeType { env, this->mimeType };

    JObjectRef jProperties { env, env->NewObject(*jPropertiesClass,
            jPropertiesInitMethod, *jMimeType,
            (jlong) properties->lengthInMilliseconds(), properties->bitrate(),
            properties->sampleRate()) };

    JClassRef jMetadataClass { env, "org/oxycblt/musikr/metadata/Metadata" };
    jmethodID jMetadataInitMethod = jMetadataClass.method("<init>",
            "(Ljava/util/Map;Ljava/util/Map;Ljava/util/Map;[BLorg/"
                    "oxycblt/musikr/metadata/Properties;)V");
    auto jId3v2Map = id3v2.getObject();
    auto jXiphMap = xiph.getObject();
    auto jMp4Map = mp4.getObject();
    if (cover.has_value() && !cover->isEmpty()) {
        JByteArrayRef jCoverArray { env, cover.value() };
        cover.reset();
        jobject result = env->NewObject(*jMetadataClass, jMetadataInitMethod,
                **jId3v2Map, **jXiphMap, **jMp4Map, *jCoverArray, *jProperties);
        checkJniException(env, "Metadata.<init> threw JNI exception");
        return result;
    }
    jobject result = env->NewObject(*jMetadataClass, jMetadataInitMethod,
            **jId3v2Map, **jXiphMap, **jMp4Map, nullptr, *jProperties);
    checkJniException(env, "Metadata.<init> threw JNI exception");
    return result;
}
