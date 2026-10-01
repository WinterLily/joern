import 'package:test/test.dart';

import '../../src/test/resources/semantics/generic_scopes.dart' as fixture;

void main() {
  test('generic scopes preserve bounded calls and nullable values', () {
    for (final text in ['', 'input']) {
      final value = fixture.Data(text);
      final box = fixture.Box(value);
      expect(identical(box.echo(value), value), isTrue);
      expect(identical(box.narrower(value), value), isTrue);
      expect(box.read(value), text);
      expect(identical(fixture.first(value), value), isTrue);
      expect(fixture.nested(value), '$text:7');
      fixture.Converter<fixture.Data> converter = fixture.first;
      fixture.Legacy<fixture.Data> legacy = fixture.first;
      fixture.Pair<fixture.Data, fixture.Data> pair = (value, value);
      expect(identical(converter(value), pair.$1), isTrue);
      expect(identical(legacy(value), pair.$2), isTrue);
    }
    for (final value in [-1, 0, 1]) {
      expect(fixture.Other<int>().echo(value), value);
      expect(fixture.second(value), value);
      expect(fixture.Unbounded<int>().optional(value), value);
    }
    expect(fixture.Unbounded<int>().optional(null), isNull);
  });
}
