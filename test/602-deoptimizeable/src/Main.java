/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

class TestObject {
    public static boolean sHashCodeInvoked = false;
    private int i;

    public TestObject(int i) {
        this.i = i;
    }

    public boolean equals(Object obj) {
        return (obj instanceof TestObject) && (i == ((TestObject)obj).i);
    }

    public int hashCode() {
        sHashCodeInvoked = true;
        Main.deoptimizeAll();
        return i % 64;
    }
}

public class Main {
    static boolean sFlag = false;

    public static native void deoptimizeAll();
    public static native void undeoptimizeAll();

    public static void execute(Runnable runnable) throws Exception {
      Thread t = new Thread(runnable);
      t.start();
      t.join();
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary(args[0]);
        final HashMap<TestObject, Long> map = new HashMap<TestObject, Long>();

        // Single-frame deoptimization that covers partial fragment.
        execute(new Runnable() {
            public void run() {
                int[] arr = new int[3];
                int res = $noinline$run1(arr);
                if (res != 79) {
                    System.out.println("Failure 1!");
                    System.exit(0);
                }
            }
        });

        // Single-frame deoptimization that covers a full fragment.
        execute(new Runnable() {
            public void run() {
                try {
                    int[] arr = new int[3];
                    // Use reflection to call $noinline$run2 so that it does
                    // full-fragment deoptimization since that is an upcall.
                    Class<?> cls = Class.forName("Main");
                    Method method = cls.getDeclaredMethod("$noinline$run2", int[].class);
                    double res = (double)method.invoke(Main.class, arr);
                    if (res != 79.3d) {
                        System.out.println("Failure 2!");
                        System.exit(0);
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                }
            }
        });

        // Full-fragment deoptimization.
        execute(new Runnable() {
            public void run() {
                float res = $noinline$run3B();
                if (res != 0.034f) {
                    System.out.println("Failure 3!");
                    System.exit(0);
                }
            }
        });

        undeoptimizeAll();  // Make compiled code useable again.

        // Partial-fragment deoptimization.
        execute(new Runnable() {
            public void run() {
                try {
                    map.put(new TestObject(10), Long.valueOf(100));
                    if (map.get(new TestObject(10)) == null) {
                        System.out.println("Expected map to contain TestObject(10)");
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                }
            }
        });

        undeoptimizeAll();  // Make compiled code useable again.

        if (!TestObject.sHashCodeInvoked) {
            System.out.println("hashCode() method not invoked!");
        }
        if (map.get(new TestObject(10)) != 100) {
            System.out.println("Wrong hashmap value!");
        }
        System.out.println("Finishing");
    }

    public static int $noinline$run1(int[] arr) {
        // Prevent inlining.
        if (sFlag) {
            throw new Error();
        }
        boolean caught = false;
        // BCE will use deoptimization for the code below.
        try {
            arr[0] = 1;
            arr[1] = 1;
            arr[2] = 1;
            // This causes AIOOBE and triggers deoptimization from compiled code.
            arr[3] = 1;
        } catch (ArrayIndexOutOfBoundsException e) {
            caught = true;
        }
        if (!caught) {
            System.out.println("Expected exception");
        }
        return 79;
    }

    public static double $noinline$run2(int[] arr) {
        // Prevent inlining.
        if (sFlag) {
            throw new Error();
        }
        boolean caught = false;
        // BCE will use deoptimization for the code below.
        try {
            arr[0] = 1;
            arr[1] = 1;
            arr[2] = 1;
            // This causes AIOOBE and triggers deoptimization from compiled code.
            arr[3] = 1;
        } catch (ArrayIndexOutOfBoundsException e) {
            caught = true;
        }
        if (!caught) {
            System.out.println("Expected exception");
        }
        return 79.3d;
    }

    public static float $noinline$run3A() {
        // Prevent inlining.
        if (sFlag) {
            throw new Error();
        }
        // Deoptimize callers.
        deoptimizeAll();
        return 0.034f;
    }

    public static float $noinline$run3B() {
        // Prevent inlining.
        if (sFlag) {
            throw new Error();
        }
        float res = $noinline$run3A();
        return res;
    }
}
