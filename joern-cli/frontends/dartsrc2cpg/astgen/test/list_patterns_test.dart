import 'package:test/test.dart';

import '../../src/test/resources/semantics/list_patterns.dart' as fixture;

void main() {
  test('list patterns skip untyped wildcards and cache member invocations', () {
    for (var length = 0; length <= 4; length++) {
      final values = List<Object?>.generate(length, (index) => index + 1);
      final input = fixture.LoggedList(values);
      fixture.trace.clear();
      expect(fixture.empty(input), length == 0);
      expect(fixture.trace, ['length']);
      fixture.trace.clear();
      expect(fixture.all(input), values);
      expect(fixture.trace, ['slice:0:null']);
      fixture.trace.clear();
      expect(
        fixture.prefixRest(input),
        length > 0 ? [1, values.sublist(1)] : [],
      );
      expect(fixture.trace, [
        'length',
        if (length > 0) ...['index:0', 'slice:1:null'],
      ]);
      fixture.trace.clear();
      expect(fixture.wildcards(input), length == 2);
      expect(fixture.trace, ['length']);
      fixture.trace.clear();
      expect(fixture.typedWildcard(input), length == 2);
      expect(fixture.trace, ['length', if (length == 2) 'index:0']);
      fixture.trace.clear();
      expect(fixture.restWildcard(input), true);
      expect(fixture.trace, isEmpty);
      fixture.trace.clear();
      expect(fixture.headAndRestWildcard(input), length >= 1);
      expect(fixture.trace, ['length']);
      fixture.trace.clear();
      expect(fixture.cases(input), length == 2 ? 1 : 'wrong length');
      expect(fixture.trace, ['length', if (length == 2) 'index:0']);
      fixture.trace.clear();
      expect(
        fixture.rest(input),
        length >= 2 ? [1, values.sublist(1, length - 1), length] : [],
      );
      expect(fixture.trace, [
        'length',
        if (length >= 2) ...[
          'index:0',
          'slice:1:${length - 1}',
          'index:${length - 1}',
        ],
      ]);
      fixture.trace.clear();
      expect(fixture.tail(input), length > 0 ? length : 'empty');
      expect(fixture.trace, ['length', if (length > 0) 'index:${length - 1}']);
    }
    for (final value in [null, 'text', 1]) {
      fixture.trace.clear();
      expect(
        fixture.typedWildcard(fixture.LoggedList([value, 0])),
        value is int,
      );
      expect(fixture.trace, ['length', 'index:0']);
    }
  });
}
