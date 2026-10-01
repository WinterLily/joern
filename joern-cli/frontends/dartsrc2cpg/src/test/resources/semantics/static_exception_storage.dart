import 'static_storage.dart';

void branch(String input, bool fail) {
  if (fail) {
    write(input);
    throw StateError('branch');
  }
  write('normal');
}

String normalBranch(String input, bool fail) {
  branch(input, fail);
  return read();
}

String caughtBranch(String input, bool fail) {
  write('before');
  try {
    branch(input, fail);
  } on StateError {
    return read();
  }
  return 'normal result';
}

void relayThrow(String input) => writeAndThrow(input);

String nestedThrow(String input) {
  write('before');
  try {
    relayThrow(input);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

String caughtOverwrite(String input) {
  try {
    writeAndThrow(input);
  } on StateError {
    write('constant');
    return read();
  }
  return 'unreachable';
}

String caughtIndependent(String input) {
  try {
    writeAndThrow(input);
  } on StateError {
    // The next selected call must supply the observed memory state.
  }
  try {
    writeAndThrow('constant');
  } on StateError {
    return read();
  }
  return 'unreachable';
}

void cleanup(String input, bool replace) {
  try {
    writeAndThrow(input);
  } finally {
    if (replace) write('cleanup');
    Shared.other = 'unrelated cleanup';
  }
}

String cleanupKilled(String input) {
  write('before');
  try {
    killCleanup(input);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

String cleanupPreserved(String input) {
  write('before');
  try {
    preserveCleanup(input);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

void killCleanup(String input) {
  try {
    writeAndThrow(input);
  } finally {
    write('cleanup');
    Shared.other = 'unrelated cleanup';
  }
}

void preserveCleanup(String input) {
  try {
    writeAndThrow(input);
  } finally {
    Shared.other = 'unrelated cleanup';
  }
}

String conditionalCleanup(String input, bool replace) {
  try {
    cleanup(input, replace);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

void suppress(String input) {
  try {
    writeAndThrow(input);
  } finally {
    return;
  }
}

String suppressedResult(String input) {
  write('before');
  suppress(input);
  return read();
}

void returnThenThrow(String input) {
  try {
    write(input);
    return;
  } finally {
    throw StateError('cleanup failure');
  }
}

String cleanupThrows(String input) {
  try {
    returnThenThrow(input);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

void replaceThrow(String input) {
  try {
    writeAndThrow(input);
  } finally {
    write('replacement');
    throw StateError('replacement failure');
  }
}

String replacedException(String input) {
  try {
    replaceThrow(input);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

void nestedCleanup(String input) {
  try {
    try {
      writeAndThrow(input);
    } finally {
      write('inner');
    }
  } finally {
    write('outer');
  }
}

String nestedCleanupKilled(String input) {
  try {
    nestedCleanup(input);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

void rethrowWrapper(String input) {
  try {
    writeAndThrow(input);
  } on StateError {
    Shared.other = 'handled';
    rethrow;
  }
}

String rethrown(String input) {
  try {
    rethrowWrapper(input);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

void swallowed(String input, bool replace) {
  try {
    writeAndThrow(input);
  } on StateError {
    if (replace) write('handled');
  }
}

String swallowedResult(String input, bool replace) {
  write('before');
  swallowed(input, replace);
  return read();
}

void joinedCleanup(String input, bool fail) {
  try {
    branch(input, fail);
  } finally {
    Shared.other = 'joined cleanup';
  }
}

String joinedNormal(String input, bool fail) {
  joinedCleanup(input, fail);
  return read();
}

String joinedCaught(String input, bool fail) {
  try {
    joinedCleanup(input, fail);
  } on StateError {
    return read();
  }
  return 'normal result';
}

void otherBranch(String input, bool fail) {
  if (fail) {
    Shared.other = input;
    throw StateError('other branch');
  }
  Shared.other = 'normal copy';
}

void copiedCleanup(String input, bool fail) {
  try {
    otherBranch(input, fail);
  } finally {
    Shared.value = Shared.other;
  }
}

String copiedNormal(String input, bool fail) {
  copiedCleanup(input, fail);
  return read();
}

String copiedCaught(String input, bool fail) {
  try {
    copiedCleanup(input, fail);
  } on StateError {
    return read();
  }
  return 'normal result';
}

String copiedBoth(String input, bool fail) {
  try {
    copiedCleanup(input, fail);
    return read();
  } on StateError {
    final caught = read();
    return caught;
  }
}

String otherValue() => Shared.other;
String identity(String value) => value;

void helperCopyCleanup(String input, bool fail) {
  try {
    otherBranch(input, fail);
  } finally {
    final saved = identity(otherValue());
    Shared.other = 'after saved read';
    Shared.value = saved;
  }
}

String helperCopyNormal(String input, bool fail) {
  helperCopyCleanup(input, fail);
  return read();
}

String helperCopyCaught(String input, bool fail) {
  try {
    helperCopyCleanup(input, fail);
  } on StateError {
    return read();
  }
  return 'normal result';
}

String copiedIndependent(String input) {
  try {
    copiedCleanup(input, true);
  } on StateError {
    // The second copy selects independent input in the same helper.
  }
  try {
    copiedCleanup('constant', true);
  } on StateError {
    return read();
  }
  return 'unreachable';
}

void localCopyCleanup(String input, bool fail) {
  var saved = 'normal local';
  try {
    if (fail) {
      saved = input;
      throw StateError('local branch');
    }
  } finally {
    Shared.value = saved;
  }
}

String localCopyNormal(String input, bool fail) {
  localCopyCleanup(input, fail);
  return read();
}

String localCopyCaught(String input, bool fail) {
  try {
    localCopyCleanup(input, fail);
  } on StateError {
    return read();
  }
  return 'normal result';
}
