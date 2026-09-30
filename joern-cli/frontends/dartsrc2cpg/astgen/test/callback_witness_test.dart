import 'package:test/test.dart';

import '../../src/test/resources/semantics/callback_witness.dart';

class Values extends Sequence {
  final List<String> values;
  final visits = <int>[];
  Values(this.values);

  @override
  Iterable<T> mapIndexed<T>(T Function(int, String) callback) sync* {
    for (var index = 0; index < values.length; index++) {
      visits.add(index);
      yield callback(index, values[index]);
    }
  }
}

void main() {
  test('mapping results do not feed back into keys or callback indices', () {
    for (final input in <List<String>>[
      [],
      ['txt'],
      ['txt', 'png'],
    ]) {
      final sequence = Values(List.of(input));
      final output = mapped(sequence).toList();
      expect(output.map((entry) => entry.key), input);
      expect(output.map((entry) => entry.value), [
        for (var index = 0; index < input.length; index++)
          '${index + 1}.${input[index]}',
      ]);
      expect(sequence.visits, [for (var i = 0; i < input.length; i++) i]);
      expect(sequence.values, input);
    }
    for (final name in ['first', 'second']) {
      expect('ext'.decorate(name), '$name.ext');
    }
  });
}
