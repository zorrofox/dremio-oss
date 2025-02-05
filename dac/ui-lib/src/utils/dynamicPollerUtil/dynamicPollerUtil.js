/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
import { v4 as uuid } from "uuid";

class DynamicPollerUtil {
  _jobs = {};

  _setIntervalExecutor(executor, interval, id) {
    setTimeout(() => {
      if (this._jobs[id]) {
        executor();
        this._setIntervalExecutor(executor, interval, id);
      }
    }, interval.next().value);
  }

  setInterval(executor, intervalGenerator) {
    const id = uuid();
    const interval = intervalGenerator();
    this._setIntervalExecutor(executor, interval, id);
    this._jobs[id] = true;
    return id;
  }

  clearInterval(id) {
    delete this._jobs[id];
  }
}

export default new DynamicPollerUtil();
