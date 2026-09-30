// Copyright (c) 2024, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

// Selected type-error cases from the pinned SDK test; no parser-recovery cases.
String? stringQuestion() => null;
void main() {
  <num>[?stringQuestion()];
  <bool>{?stringQuestion()};
}
