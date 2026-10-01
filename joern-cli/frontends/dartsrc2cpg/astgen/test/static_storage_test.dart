import 'package:test/test.dart';

import '../../src/test/resources/semantics/static_storage.dart' as fixture;

void main() {
  test(
    'static writes preserve order, independent locations and saved reads',
    () {
      for (final input in ['', 'input', 'second']) {
        expect(fixture.direct(input), input);
        expect(fixture.nested(input), input);
        expect(fixture.repeated(input), input);
        expect(fixture.overwritten(input), 'constant');
        expect(fixture.independent(input), 'constant');
        expect(fixture.Shared.other, input);
        expect(fixture.differentOwner(input), 'constant');
        expect(fixture.Separate.value, input);
        fixture.write('before');
        expect(fixture.before(input), 'before');
        expect(fixture.read(), input);
        expect(fixture.returnedAfterRead(input), input);
        expect(fixture.read(), 'constant');
        expect(fixture.lastCall(input), input);
        expect(fixture.independentCall(input), 'constant');
        expect(fixture.exceptional(input), input);
        for (final choose in [false, true]) {
          expect(
            fixture.conditional(input, choose),
            choose ? 'constant' : input,
          );
          expect(fixture.looped(input, choose), choose ? 'constant' : input);
          expect(
            fixture.bothBranches(input, choose),
            choose ? 'left' : 'right',
          );
        }
      }
    },
  );
}
