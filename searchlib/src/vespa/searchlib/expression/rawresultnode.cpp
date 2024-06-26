// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "rawbucketresultnode.h"

namespace search {
namespace expression {

const BucketResultNode& RawResultNode::getNullBucket() const {
    return RawBucketResultNode::getNull();
}

}
}

