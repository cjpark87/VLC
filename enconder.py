import cv2
import numpy as np
import time
import scipy as sp
import argparse
import bitarray
import threading


#FREQ_1 = 10
#FREQ_0 = 5
FRAME_DURATION = 1.0/30.0 * 6.0 * 1000
PREAMBLE = [False, True]*4


def encode(msg):
    ba = bitarray.bitarray()
    ba.frombytes(msg.encode('utf-8'))
    return ba

def update_img(data, alpha):
    output = cv2.addWeighted(img, alpha, img_bg, 1 - alpha, 0)

    #interval_1 = 1/FREQ_1 / FRAME_INTERVAL * 1000
    #interval_0 = 1/FREQ_0 / FRAME_INTERVAL * 1000

    cv2.imshow('data', img)
    cv2.waitKey(10000)


    print(PREAMBLE)
    print(data)

    for i, d in enumerate(PREAMBLE + list(data)):#+list(data):
        samples = 2 if d else 1

        print(i, samples, d, FRAME_DURATION/samples)

        for t in range(1, samples+1):
            interval = FRAME_DURATION/samples/2

            prev = time.time()
            cv2.imshow('data', output)
            cv2.waitKey(1)
            gap = time.time()-prev
            if interval/1000 > gap:
                time.sleep((interval/1000)-gap)

            print(time.time() - prev)

            prev = time.time()
            cv2.imshow('data', img)
            cv2.waitKey(1)
            gap = time.time()-prev
            if interval/1000 > gap:
                time.sleep((interval/1000)-gap)

            print(time.time() - prev)

def show_img():
    cv2.imshow('data', img_output)
    cv2.waitKey(60*1000)

def main():
    global args
    global img
    global img_bg
    global img_black
    global img_output

    img = cv2.imread('uw.png')
    img = cv2.resize(img, (0,0), fx=0.3, fy=0.3)
    img_bg = img.copy()
    img_bg.fill(255)
    img_black = img.copy()
    img_black.fill(0)

    img_output = img.copy()

    parser = argparse.ArgumentParser(description='Process some integers.')
    parser.add_argument('-m', '--message', type=str, default='Hello world!')
    parser.add_argument('-a', '--alpha', type=float, default='0.5')
    args = parser.parse_args()

    data = encode(args.message)
    print(args)

    cv2.namedWindow('data', cv2.WINDOW_KEEPRATIO)
    cv2.setWindowProperty('data', cv2.WND_PROP_ASPECT_RATIO, cv2.WINDOW_KEEPRATIO)
    cv2.setWindowProperty('data', cv2.WND_PROP_FULLSCREEN, cv2.WINDOW_FULLSCREEN)
    cv2.moveWindow('data', 0, 0)
    cv2.imshow('data', img)
    cv2.waitKey(1)

    time.sleep(3)
    update_img(data, args.alpha)

if __name__ == "__main__":
    main()


#img = cv2.imread('/Users/cjpark/Downloads/uw.png')
#white_img = img.copy()
#white_img.fill(255)
#cv2.imwrite('white.png', white_img)
#output = None
#alpha = 1
#while alpha > 0:
#    print('a', alpha)
#    output = cv2.addWeighted(img, alpha, white_img, 1 - alpha, 0)
#    cv2.imshow('encoding', output)
#    cv2.waitKey(1)
#    alpha -= 0.1
#    time.sleep(0.5)
