import 'package:test/test.dart';

import '../../src/test/resources/semantics/extension_cache.dart' as fixture;

void main() {
  test('extension invocation reuse retains generic substitutions', () {
    for (final value in [0, 1, 2]) {
      final input = fixture.Box(value);
      fixture.trace.clear();
      expect(fixture.repeated(input), 'int');
      expect(fixture.trace, ['kind:int']);
      fixture.trace.clear();
      expect(fixture.substituted(input), 'num');
      expect(fixture.trace, ['kind:int', 'kind:num']);
      fixture.trace.clear();
      expect(fixture.compared(input), value > 1);
      expect(fixture.trace, ['greater:int:1']);
    }
  });
}
