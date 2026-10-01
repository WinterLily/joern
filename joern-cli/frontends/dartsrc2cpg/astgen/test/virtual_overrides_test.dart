import 'package:test/test.dart';

import '../../src/test/resources/semantics/virtual_overrides.dart' as fixture;

void main() {
  test(
    'covariant and renamed generic overrides select runtime implementations',
    () {
      for (final input in ['', 'input']) {
        for (final ignored in ['', 'ignored']) {
          for (final (receiver, label) in <(fixture.Concrete, String)>[
            (fixture.Concrete(), 'concrete'),
            (fixture.Mixed(), 'layer'),
            (fixture.Descendant(), 'concrete'),
          ]) {
            for (final call in [
              fixture.invoke,
              fixture.bound,
              fixture.concrete,
            ]) {
              fixture.trace.clear();
              expect(call(receiver, input, ignored), input);
              expect(fixture.trace, ['$label:echo']);
            }
            for (final call in [fixture.generic, fixture.genericBound]) {
              fixture.trace.clear();
              expect(call(receiver, input, ignored), input);
              expect(fixture.trace, ['$label:generic']);
            }
            for (final call in [fixture.getter, fixture.plus, fixture.index]) {
              expect(call(receiver, input), input);
            }
            fixture.trace.clear();
            expect(
              () => (receiver as fixture.Surface).echo(1, 2),
              throwsA(isA<TypeError>()),
            );
            expect(fixture.trace, isEmpty);
          }
        }
      }
    },
  );
}
