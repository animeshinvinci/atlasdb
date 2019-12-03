/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.lock.watch;

import java.util.OptionalLong;

import org.immutables.value.Value;

@Value.Immutable
public interface LockWatchInfo {
        LockWatchInfo NOT_WATCHED = ImmutableLockWatchInfo.of(State.NOT_WATCHED, OptionalLong.empty());

        @Value.Parameter
        State state();
        @Value.Parameter
        OptionalLong lastLocked();

        static LockWatchInfo of(State state, OptionalLong lastLocked) {
                return ImmutableLockWatchInfo.of(state, lastLocked);
        }

        static LockWatchInfo of(State state, long lastLocked) {
                return ImmutableLockWatchInfo.of(state, OptionalLong.of(lastLocked));
        }

        enum State {
                LOCKED, UNLOCKED, NOT_WATCHED
        }
}