// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/document/base/globalid.h>
#include <vespa/document/fieldvalue/document.h>
#include <vespa/document/util/bytebuffer.h>
#include <vespa/vespalib/objects/nbostream.h>
#include <vespa/vespalib/util/growablebytebuffer.h>

namespace storage::mbusprot {

class SerializationHelper
{
public:
    static int32_t getInt(document::ByteBuffer& buf) {
        int32_t tmp;
        buf.getIntNetwork(tmp);
        return tmp;
    }

    static int16_t getShort(document::ByteBuffer& buf) {
        int16_t tmp;
        buf.getShortNetwork(tmp);
        return tmp;
    }

    static uint8_t getByte(document::ByteBuffer& buf) {
        uint8_t tmp;
        buf.getByte(tmp);
        return tmp;
    }

    static std::string_view getString(document::ByteBuffer& buf) {
        uint32_t tmp;
        buf.getIntNetwork((int32_t&) tmp);
        const char * p = buf.getBufferAtPos();
        buf.incPos(tmp);
        std::string_view s(p, tmp);
        return s;
    }

    static document::GlobalId getGlobalId(document::ByteBuffer& buf) {
        std::vector<char> buffer(getShort(buf));
        for (uint32_t i=0; i<buffer.size(); ++i) {
            buffer[i] = getByte(buf);
        }
        return document::GlobalId(&buffer[0]);
    }

    static document::Document::UP getDocument(document::ByteBuffer& buf, const document::DocumentTypeRepo& repo)
    {
        uint32_t size = getInt(buf);
        if (size == 0) {
            return document::Document::UP();
        } else {
            vespalib::nbostream stream(buf.getBufferAtPos(), size);
            buf.incPos(size);
            return std::make_unique<document::Document>(repo, stream);
        }
    }

    static void putDocument(document::Document* doc, vespalib::GrowableByteBuffer& buf)
    {
        if (doc) {
            vespalib::nbostream stream;
            doc->serialize(stream);
            buf.putInt(stream.size());
            buf.putBytes(stream.peek(), stream.size());
        } else {
            buf.putInt(0);
        }
    }

};

}
