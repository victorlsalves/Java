import java.util.Scanner;

public class teste {
    // Method to check if a number is prime
    public static boolean isPrime(int num) {
        if (num <= 1) {
            return false; // Not prime
        }
        for (int i = 2; i <= Math.sqrt(num); i++) {
            if (num % i == 0) {
                return false; // Not prime
            }
        }
        return true; // Prime
    }

    // Method to print divisors of a number
    public static void printDivisors(int num) {
        System.out.print("Divisors of " + num + ": ");
        for (int i = 1; i <= num; i++) {
            if (num % i == 0) {
                System.out.print(i + " ");
            }
        }
        System.out.println();
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter a number: ");
        int number = scanner.nextInt();

        // Check if the number is even or odd
        if (number % 2 == 0) {
            System.out.println(number + " is Even");
        } else {
            System.out.println(number + " is Odd");
        }

        // Check if the number is prime
        if (isPrime(number)) {
            System.out.println(number + " is a Prime number");
        } else {
            System.out.println(number + " is not a Prime number");
        }

        // Print the divisors of the number
        printDivisors(number);

        scanner.close();
    }
}
