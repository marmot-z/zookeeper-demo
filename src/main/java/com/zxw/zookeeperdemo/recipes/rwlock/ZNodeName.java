/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zxw.zookeeperdemo.recipes.rwlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Represents an immutable ephemeral znode name which has an ordered sequence
 * number and can be sorted in order. The expected name format of the znode is
 * as follows:
 *
 * <pre>
 * &lt;name&gt;-&lt;sequence&gt;
 *
 * For example: lock-00001
 * </pre>
 */
class ZNodeName implements Comparable<ZNodeName> {

    private static final Logger LOG = LoggerFactory.getLogger(ZNodeName.class);

    private final String name;
    private final NodeType type;
    private final Integer sequence;

    /**
     * Instantiate a ZNodeName with the provided znode name.
     *
     * @param name The name of the znode
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public ZNodeName(final String name) {
        this.name = Objects.requireNonNull(name, "ZNode name cannot be null");

        // name 格式：write/read-id-sequence
        String[] parts = name.split("-");
        int partsLen = parts.length;
        this.type = NodeType.from(parts[partsLen - 2]);
        this.sequence = parseSequenceString(parts[partsLen - 1]);
    }

    private Integer parseSequenceString(final String seq) {
        return Integer.parseInt(seq);
    }

    @Override
    public String toString() {
        return "ZNodeName{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", sequence=" + sequence +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ZNodeName other = (ZNodeName) o;

        return name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /**
     * Compare znodes based on their sequence number.
     *
     * @param that other znode to compare to
     * @return the difference between their sequence numbers: a positive value if this
     *         znode has a larger sequence number, 0 if they have the same sequence number
     *         or a negative number if this znode has a lower sequence number
     */
    public int compareTo(final ZNodeName that) {
        return this.sequence.compareTo(that.sequence);
    }

    /**
     * Returns the name of the znode.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the optional sequence number.
     */
    public Integer getSequence() {
        return sequence;
    }

    public NodeType getType() {
        return type;
    }

    enum NodeType {
        WRITE,
        READ;

        public static NodeType from(String type) {
            for (NodeType nodeType : NodeType.values()) {
                if (nodeType.name().equalsIgnoreCase(type)) {
                    return nodeType;
                }
            }

            throw new IllegalArgumentException("Unknown node type:" + type);
        }
    }
}
