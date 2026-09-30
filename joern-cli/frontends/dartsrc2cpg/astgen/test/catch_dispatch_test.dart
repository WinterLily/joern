import 'package:test/test.dart';

import '../../src/test/resources/semantics/catch_dispatch.dart' as fixture;

void main() {
  setUp(fixture.trace.clear);

  test('typed clauses select the first match and finally runs afterward', () {
    final cases = [
      (const FormatException('format input'), 'format input', 'format'),
      (StateError('state input'), 'state input', 'state'),
      ('plain input', 'plain input', 'fallback'),
    ];
    for (final (input, output, handler) in cases) {
      fixture.trace.clear();
      expect(fixture.choose(input), output);
      expect(fixture.trace, [handler, 'finally']);
    }
    fixture.trace.clear();
    expect(fixture.overlapping(const FormatException('input')), 'first');
    expect(fixture.trace, ['first']);
  });

  test(
    'unmatched clauses propagate and rethrow preserves the caught object',
    () {
      final error = StateError('input');
      expect(() => fixture.filtered(error), throwsA(same(error)));
      expect(fixture.filtered(const FormatException('handled')), 'handled');
      expect(fixture.nested('input'), 'input');
      expect(fixture.trace, ['inner', 'outer']);
      fixture.trace.clear();
      expect(identical(fixture.nested(error), error), isTrue);
      expect(fixture.trace, ['outer']);
    },
  );

  test('a failure handled inside cleanup preserves the pending return', () {
    expect(fixture.preservedReturn('input'), 'input');
    expect(fixture.trace, ['handled']);
  });
}
