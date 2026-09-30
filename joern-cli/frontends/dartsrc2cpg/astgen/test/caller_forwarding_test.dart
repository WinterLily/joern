import 'package:test/test.dart';

import '../../src/test/resources/semantics/caller_forwarding.dart' as fixture;

void main() {
  test('read-only forwarding preserves its input and separates invocations', () {
    for (final input in ['', 'a', 'b', ' a ', ' b ']) {
      expect(fixture.relay(input), input);
      expect(fixture.caller(input), input.trim());
      expect(fixture.separate(input), 'fixed');
    }
  });
}
