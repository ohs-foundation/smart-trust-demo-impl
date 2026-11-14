/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.trustdemo.onesa.feature.role

import dev.ohs.trustdemo.onesa.auth.AppRole
import dev.ohs.trustdemo.onesa.generated.resources.Res
import dev.ohs.trustdemo.onesa.generated.resources.role_chw
import dev.ohs.trustdemo.onesa.generated.resources.role_clinician
import dev.ohs.trustdemo.onesa.generated.resources.role_family
import org.jetbrains.compose.resources.StringResource

/** What the role is called on screen. */
val AppRole.label: StringResource
  get() =
    when (this) {
      AppRole.CHW -> Res.string.role_chw
      AppRole.CLINICIAN -> Res.string.role_clinician
      AppRole.FAMILY -> Res.string.role_family
    }
