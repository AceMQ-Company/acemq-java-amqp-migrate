/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.migrate;

import java.nio.file.Path;

/**
 * One use of a source API: where it is, which row of the table it matched, and the text that
 * matched. HIGH confidence for an import (an exact type), MEDIUM for a usage pattern.
 */
public record Finding(Path file, int line, Mapping mapping, String sourceApi, Confidence confidence) {

    public enum Confidence { HIGH, MEDIUM }

    public String where() {
        return file.toString().replace('\\', '/') + ":" + line;
    }
}
