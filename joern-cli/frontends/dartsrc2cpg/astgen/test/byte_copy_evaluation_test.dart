import 'dart:typed_data';

import 'package:test/test.dart';

import '../../src/test/resources/semantics/byte_copy.dart' as fixture;

void main() {
  test(
    'bounded byte copies preserve contents while size-only and separate buffers remain zero',
    () {
      final inputs = <List<int>>[
        [],
        for (final first in [0, 1, 255]) [first],
        for (final first in [0, 1, 255])
          for (final second in [0, 1, 255]) [first, second],
      ];
      for (final values in inputs) {
        final input = Uint8List.fromList(values);
        expect(fixture.copy(input), values);
        expect(fixture.allocate(input), List<int>.filled(values.length, 0));
        expect(fixture.separate(input), List<int>.filled(values.length, 0));
        expect(fixture.sourceIsolation(input), [0, 0]);
      }
    },
  );
}
