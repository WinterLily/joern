import 'package:test/test.dart';

import '../../src/test/resources/semantics/static_dispatch.dart' as fixture;

void main() {
  test(
    'super and extension operations retain their selected implementations',
    () {
      for (final input in ['', 'input']) {
        for (final ignored in ['', 'ignored']) {
          final value = fixture.Derived();
          for (final call in [
            value.directSuper,
            value.tearoffSuper,
            value.invokeSuper,
            fixture.staticTearoff,
            fixture.extensionTearoff,
            fixture.extensionInvoke,
          ]) {
            expect(call(input, ignored), input);
          }
          expect(fixture.virtual(fixture.Base(), input, ignored), input);
          expect(fixture.virtual(value, input, ignored), ignored);
          fixture.trace.clear();
          for (final call in [
            value.getterSuper,
            value.setterSuper,
            value.operatorSuper,
            value.indexSuper,
            value.indexSetSuper,
          ]) {
            expect(call(input), input);
          }
          expect(fixture.trace, isEmpty);
          fixture.extensionSetter(input);
          expect(fixture.trace, ['extension:$input']);
        }
      }
    },
  );
}
