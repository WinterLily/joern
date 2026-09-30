import 'package:test/test.dart';

class Counter {
  final int value;
  final List<String> trace;
  Counter(this.value, this.trace);

  Counter operator +(int amount) {
    trace.add('add:$amount');
    return Counter(value + amount, trace);
  }

  Counter operator -() {
    trace.add('negate');
    return Counter(-value, trace);
  }
}

class Store {
  Counter value;
  final List<String> trace;
  Store(int input, this.trace) : value = Counter(input, trace);

  Counter operator [](int index) {
    trace.add('read:$index');
    return value;
  }

  void operator []=(int index, Counter next) {
    trace.add('write:$index:${next.value}');
    value = next;
  }
}

void main() {
  test('a null indexed receiver skips index and right-hand evaluation', () {
    final trace = <String>[];
    Store? receiver() {
      trace.add('receiver');
      return null;
    }

    int index() {
      trace.add('index');
      return 0;
    }

    Counter value() {
      trace.add('value');
      return Counter(1, trace);
    }

    receiver()?[index()] = value();
    expect(trace, ['receiver']);
  });
  test('overloaded indexed updates evaluate their location once in order', () {
    for (var input = -1; input <= 1; input++) {
      for (final prefix in [false, true]) {
        final trace = <String>[];
        final store = Store(input, trace);
        Store receiver() {
          trace.add('receiver');
          return store;
        }

        int index() {
          trace.add('index');
          return 0;
        }

        final result = prefix ? ++receiver()[index()] : receiver()[index()]++;
        expect(result.value, prefix ? input + 1 : input);
        expect(store.value.value, input + 1);
        expect(trace, [
          'receiver',
          'index',
          'read:0',
          'add:1',
          'write:0:${input + 1}',
        ]);
        trace.clear();
        receiver()[index()] += 2;
        expect(trace, [
          'receiver',
          'index',
          'read:0',
          'add:2',
          'write:0:${input + 3}',
        ]);
        expect((-store.value).value, -(input + 3));
        expect(trace.last, 'negate');
      }
    }
  });
}
