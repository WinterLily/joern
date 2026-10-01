import 'package:test/test.dart';

import '../../src/test/resources/semantics/mixin_super.dart' as fixture;

void main() {
  test(
    'mixin super targets precede each application and exclude overrides',
    () {
      for (final input in ['', 'input']) {
        for (final ignored in ['', 'ignored']) {
          for (final (value, label) in <(fixture.Probe, String)>[
            (fixture.Direct(), 'base'),
            (fixture.Applied(), 'prefix'),
            (fixture.Stacked(), 'prior'),
            (fixture.Named(), 'prefix'),
            (fixture.End(), 'prefix'),
          ]) {
            for (final call in [value.invoke, value.bound]) {
              fixture.trace.clear();
              expect(call(input, ignored), input);
              expect(fixture.trace, ['$label:echo']);
            }
            expect(value.echo(input, ignored), ignored);
            for (final (call, name) in [
              (value.getter, 'read'),
              (value.setter, 'write'),
              (value.plus, '+'),
              (value.index, '[]'),
              (value.indexSet, '[]='),
            ]) {
              fixture.trace.clear();
              expect(call(input), input);
              expect(fixture.trace, ['$label:$name']);
            }
          }
        }
      }
    },
  );
}
